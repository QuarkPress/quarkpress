<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tier B scenario driver: installs a real WordPress into MySQL and runs scenarios against it.
 *   php scenario-driver.php install <wordpress-dir> <input.json> <output.json>
 *   php scenario-driver.php run     <wordpress-dir> <input.json> <output.json>
 * The database server comes from QP_DB_HOST, QP_DB_PORT, QP_DB_USER and QP_DB_PASSWORD. Every call is its own PHP
 * process: WordPress keeps its state in constants and globals, which one process cannot reset. The driver calls
 * WordPress functions; the two rules it applies itself are marked where they happen.
 */

declare(strict_types=1);

if (5 !== $argc) {
    fwrite(STDERR, "usage: php scenario-driver.php install|run <wordpress-dir> <input.json> <output.json>\n");
    exit(2);
}
[, $qp_mode, $qp_wordpress, $qp_input_file, $qp_output_file] = $argv;

function qp_write(string $file, array $data): void
{
    file_put_contents($file, json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR));
}

function qp_env(string $name): string
{
    $value = getenv($name);
    if (false === $value || '' === $value) {
        throw new RuntimeException("the environment variable $name is not set");
    }
    return $value;
}

function qp_server(): mysqli
{
    mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);
    return new mysqli(qp_env('QP_DB_HOST'), qp_env('QP_DB_USER'), qp_env('QP_DB_PASSWORD'), '', (int) qp_env('QP_DB_PORT'));
}

function qp_identifier(string $name): string
{
    if (1 !== preg_match('/^[a-z0-9_]{1,64}$/', $name)) {
        throw new InvalidArgumentException("not a scenario database name: $name");
    }
    return '`' . $name . '`';
}

function qp_create_database(string $name): void
{
    $server = qp_server();
    $server->query('CREATE DATABASE ' . qp_identifier($name) . ' CHARACTER SET utf8mb4');
    $server->close();
}

/** Copies every table of the template database, with its rows, into a new database. */
function qp_clone_database(string $template, string $target): void
{
    qp_create_database($target);
    $server = qp_server();
    // The tables carry zero-date defaults (as WordPress's own tables do); MySQL 9's default mode refuses to copy them.
    $server->query("SET SESSION sql_mode = ''");
    $tables = $server->query('SHOW TABLES FROM ' . qp_identifier($template));
    while ($row = $tables->fetch_row()) {
        $table = '`' . str_replace('`', '``', $row[0]) . '`';
        $from = qp_identifier($template) . '.' . $table;
        $to = qp_identifier($target) . '.' . $table;
        $server->query("CREATE TABLE $to LIKE $from");
        $server->query("INSERT INTO $to SELECT * FROM $from");
    }
    $server->close();
}

/** Defines what a WordPress bootstrap reads from wp-config.php and from the web server, for the variant's network. */
function qp_prepare(string $wordpress, string $database, array $variant, bool $multisite): void
{
    define('ABSPATH', rtrim($wordpress, '/') . '/');
    define('DB_NAME', $database);
    define('DB_USER', qp_env('QP_DB_USER'));
    define('DB_PASSWORD', qp_env('QP_DB_PASSWORD'));
    define('DB_HOST', qp_env('QP_DB_HOST') . ':' . qp_env('QP_DB_PORT'));
    define('DB_CHARSET', 'utf8mb4');
    define('DB_COLLATE', '');
    foreach (['AUTH_KEY', 'SECURE_AUTH_KEY', 'LOGGED_IN_KEY', 'NONCE_KEY', 'AUTH_SALT', 'SECURE_AUTH_SALT',
                 'LOGGED_IN_SALT', 'NONCE_SALT'] as $key) {
        define($key, 'qp-scenario-' . strtolower($key));
    }
    define('WP_DEBUG', false);
    define('DISABLE_WP_CRON', true);
    define('WP_HTTP_BLOCK_EXTERNAL', true);
    define('AUTOMATIC_UPDATER_DISABLED', true);
    if ($multisite) {
        // The constants WordPress's network setup asks for, so the lookup takes its usual path.
        define('MULTISITE', true);
        define('SUBDOMAIN_INSTALL', (bool) $variant['subdomain_install']);
        define('DOMAIN_CURRENT_SITE', $variant['domain']);
        define('PATH_CURRENT_SITE', $variant['path']);
        define('SITE_ID_CURRENT_SITE', 1);
        define('BLOG_ID_CURRENT_SITE', 1);
    } else {
        define('WP_INSTALLING', true);
    }
    $_SERVER['HTTP_HOST'] = $variant['domain'];
    $_SERVER['SERVER_NAME'] = $variant['domain'];
    $_SERVER['SERVER_PORT'] = '80';
    $_SERVER['REQUEST_METHOD'] = 'GET';
    $_SERVER['REQUEST_URI'] = $variant['path'];
    $_SERVER['SCRIPT_NAME'] = $variant['path'] . 'index.php';
    $_SERVER['PHP_SELF'] = $_SERVER['SCRIPT_NAME'];
}

/** No mail and no HTTP requests leave the installation (the installer and the network setup would make some). */
function qp_offline(): void
{
    add_filter('pre_wp_mail', '__return_true');
    add_filter('pre_http_request', static fn() => new WP_Error('qp_offline', 'the scenario driver makes no HTTP requests'), 10, 0);
}

/**
 * WordPress's network admin "Add Site" screen refuses, on subdirectory installs, a first segment after the network
 * path that WordPress reserves; wp_insert_site itself does not. Scenarios give explicit addresses, so the driver asks
 * WordPress for the reserved names and applies the screen's rule to sites on the network domain below the network path.
 */
function qp_reserved_segment(string $domain, string $path, array $variant): ?string
{
    if ((bool) $variant['subdomain_install'] || strtolower($domain) !== strtolower($variant['domain'])) {
        return null;
    }
    $site_path = strtolower('/' . trim($path, '/') . '/');
    $network_path = strtolower($variant['path']);
    if (!str_starts_with($site_path, $network_path) || $site_path === $network_path) {
        return null;
    }
    $first = explode('/', substr($site_path, strlen($network_path)))[0];
    return in_array($first, get_subdirectory_reserved_names(), true) ? $first : null;
}

function qp_create_site(array $op, array $variant, array &$created): array
{
    $index = count($created) + 1;
    $reserved = qp_reserved_segment($op['domain'], $op['path'], $variant);
    if (null !== $reserved) {
        $created[$index] = null;
        return ['op' => 'create_site', 'error' => 'reserved', 'segment' => $reserved];
    }
    $data = ['domain' => $op['domain'], 'path' => $op['path'], 'network_id' => 1, 'user_id' => 1, 'public' => 1];
    if ('' !== $op['title']) {
        // An empty scenario title means "no title": WordPress then names the site "Site <id>".
        $data['title'] = $op['title'];
    }
    $id = wp_insert_site($data);
    if (is_wp_error($id)) {
        $created[$index] = null;
        return ['op' => 'create_site', 'error' => $id->get_error_code()];
    }
    $created[$index] = (int) $id;
    return ['op' => 'create_site', 'id' => (int) $id];
}

/**
 * Looks a request up as WordPress's multisite bootstrap does: the host lower-cased, as the bootstrap lower-cases
 * HTTP_HOST, then ms_load_current_site_and_network decides. The globals it sets are restored afterwards.
 */
function qp_lookup(array $op, array $variant): array
{
    global $current_site, $current_blog;
    $saved_site = $current_site;
    $saved_blog = $current_blog;
    try {
        $answer = ms_load_current_site_and_network(strtolower($op['host']), $op['path'], (bool) $variant['subdomain_install']);
        if (true === $answer) {
            return ['op' => 'lookup', 'result' => 'site', 'id' => (int) $current_blog->blog_id];
        }
        if (false === $answer) {
            return ['op' => 'lookup', 'result' => 'not_installed'];
        }
        return ['op' => 'lookup', 'result' => 'redirect', 'to' => (string) $answer];
    } finally {
        $current_site = $saved_site;
        $current_blog = $saved_blog;
    }
}

function qp_set_flag(array $op, array $created): array
{
    $id = $created[$op['site']] ?? null;
    if (null === $id) {
        return ['op' => 'set_flag', 'missing' => true];
    }
    $result = update_blog_status($id, $op['flag'], $op['value'] ? '1' : '0');
    return ['op' => 'set_flag', 'ok' => false !== $result];
}

function qp_read_options(array $op, array $created): array
{
    $id = 0 === $op['site'] ? get_main_site_id() : ($created[$op['site']] ?? null);
    if (null === $id) {
        return ['op' => 'read_options', 'missing' => true];
    }
    $values = [];
    foreach ($op['names'] as $name) {
        $value = get_blog_option($id, $name, null);
        // Read back from the database every scalar option is text; a value still cached from the request is cast.
        $values[$name] = null === $value ? null : (string) $value;
    }
    return ['op' => 'read_options', 'values' => $values];
}

function qp_run_scenario(array $scenario, array $variant): array
{
    $segments = (int) $variant['path_segments'];
    if ($segments > 1) {
        // QuarkPress's path-segments counts segments the way WordPress's own count of 1 does (after the network path
        // where WordPress adds it), so the filter adds the difference to every count WordPress computes.
        add_filter('site_by_path_segments_count', static fn($count) => null === $count ? null : $count + $segments - 1, 10, 1);
    }
    $created = [];
    $results = [];
    foreach ($scenario['ops'] as $op) {
        $results[] = match ($op['op']) {
            'create_site' => qp_create_site($op, $variant, $created),
            'lookup' => qp_lookup($op, $variant),
            'set_flag' => qp_set_flag($op, $created),
            'read_options' => qp_read_options($op, $created),
            default => throw new InvalidArgumentException('unknown op ' . $op['op']),
        };
    }
    $sites = [];
    foreach ($created as $index => $id) {
        if (null === $id) {
            continue;
        }
        clean_blog_cache($id);
        $site = get_site($id);
        $sites[] = [
            'index' => $index,
            'id' => $id,
            'domain' => $site->domain,
            'path' => $site->path,
            'public' => (int) $site->public,
            'archived' => (int) $site->archived,
            'mature' => (int) $site->mature,
            'spam' => (int) $site->spam,
            'deleted' => (int) $site->deleted,
        ];
    }
    return ['main' => get_main_site_id(), 'results' => $results, 'sites' => $sites];
}

$qp_input = json_decode((string) file_get_contents($qp_input_file), true, 512, JSON_THROW_ON_ERROR);
$qp_variant = $qp_input['variant'];
try {
    if ('install' === $qp_mode) {
        qp_create_database($qp_input['database']);
        qp_prepare($qp_wordpress, $qp_input['database'], $qp_variant, false);
        $table_prefix = 'wp_';
        require ABSPATH . 'wp-settings.php';
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        qp_offline();
        $qp_installed = wp_install('QuarkPress', 'admin', 'admin@example.test', 1, '', 'scenario-password-1');
        $qp_url = 'http://' . $qp_variant['domain'] . rtrim($qp_variant['path'], '/');
        update_option('siteurl', $qp_url);
        update_option('home', $qp_url);
        $qp_single = [
            'installed' => is_blog_installed(),
            'blogname' => get_option('blogname'),
            'user_id' => (int) $qp_installed['user_id'],
        ];
        // Network creation on a single site, as WordPress's network setup performs it: the network tables get their
        // names, then the tables, the network and its main site are created.
        foreach ($wpdb->tables('ms_global') as $qp_table => $qp_prefixed_table) {
            $wpdb->$qp_table = $qp_prefixed_table;
        }
        install_network();
        $qp_network = populate_network(1, $qp_variant['domain'], 'admin@example.test', 'QuarkPress Network',
            $qp_variant['path'], (bool) $qp_variant['subdomain_install']);
        // A subdomain network warns when its wildcard DNS check fails; the driver allows no HTTP, so it always does.
        if (is_wp_error($qp_network) && 'no_wildcard_dns' !== $qp_network->get_error_code()) {
            throw new RuntimeException('populate_network: ' . $qp_network->get_error_code());
        }
        $qp_subdomain = $wpdb->get_var($wpdb->prepare(
            "SELECT meta_value FROM {$wpdb->sitemeta} WHERE site_id = %d AND meta_key = %s", 1, 'subdomain_install'));
        qp_write($qp_output_file, ['out' => [
            'single_site' => $qp_single,
            'network' => [
                'subdomain_install' => (bool) $qp_subdomain,
                'sites' => (int) $wpdb->get_var("SELECT COUNT(*) FROM {$wpdb->blogs}"),
            ],
        ]]);
    } elseif ('run' === $qp_mode) {
        qp_clone_database($qp_input['template'], $qp_input['database']);
        qp_prepare($qp_wordpress, $qp_input['database'], $qp_variant, true);
        $table_prefix = 'wp_';
        require ABSPATH . 'wp-settings.php';
        qp_offline();
        qp_write($qp_output_file, ['out' => qp_run_scenario($qp_input['scenario'], $qp_variant)]);
    } else {
        throw new InvalidArgumentException("unknown mode $qp_mode");
    }
} catch (Throwable $qp_failure) {
    qp_write($qp_output_file, ['error' => get_class($qp_failure) . ': ' . $qp_failure->getMessage()]);
    fwrite(STDERR, $qp_failure . "\n");
    exit(1);
}
