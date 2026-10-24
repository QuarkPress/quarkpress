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
 * WordPress functions; every rule it applies itself is marked "Driver-applied rule" where it happens.
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
function qp_prepare(string $wordpress, string $database, array $variant, bool $multisite,
    ?int $revisionsKeep = null): void
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
    // Driver-applied rule: a scenario's revisions_keep is WP_POST_REVISIONS for its process (the default, true, is
    // every revision), as a site owner sets it in wp-config.php.
    if (null !== $revisionsKeep) {
        define('WP_POST_REVISIONS', $revisionsKeep);
    }
    // Driver-applied rule: WordPress's REST autosaves controller defines DOING_AUTOSAVE for the rest of its request
    // unless core's tests are running; this process runs a whole scenario, so the constant must not stick to it.
    define('WP_RUN_CORE_TESTS', true);
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

/**
 * Creates a user as the network's user creation does. Driver-applied rule (wpmu_create_user): after wp_insert_user,
 * the user's capabilities and level on the main site are deleted, so a new network user is a member of no site.
 */
function qp_create_user(array $op, array &$users): array
{
    $index = count($users) + 1;
    $data = [
        'user_login' => $op['username'],
        'user_pass' => 'scenario-password-1',
        'user_email' => 'user' . $index . '@example.test',
    ];
    if ('' !== $op['given_name']) {
        $data['first_name'] = $op['given_name'];
    }
    if ('' !== $op['family_name']) {
        $data['last_name'] = $op['family_name'];
    }
    $id = wp_insert_user($data);
    if (is_wp_error($id)) {
        $users[$index] = null;
        return ['op' => 'create_user', 'error' => $id->get_error_code()];
    }
    delete_user_option($id, 'capabilities');
    delete_user_option($id, 'user_level');
    clean_user_cache($id);
    $user = get_userdata($id);
    $users[$index] = (int) $id;
    return ['op' => 'create_user', 'id' => (int) $id, 'nicename' => $user->user_nicename, 'display_name' => $user->display_name];
}

function qp_site_id(int $index, array $created): ?int
{
    return 0 === $index ? get_main_site_id() : ($created[$index] ?? null);
}

/** Runs $work for a user (index; 0 for nobody) on a site (index), or reports a site or user that was not created. */
function qp_on_site(string $kind, array $op, array $created, array $users, callable $work): array
{
    $site = qp_site_id($op['site'], $created);
    $user = 0 === ($op['user'] ?? 0) ? 0 : ($users[$op['user']] ?? null);
    if (null === $site || null === $user) {
        return ['op' => $kind, 'missing' => true];
    }
    switch_to_blog($site);
    try {
        return ['op' => $kind] + $work($site, $user);
    } finally {
        restore_current_blog();
    }
}

/** The result of a call that returns true or a WP_Error: ok, or the error's code. */
function qp_true_or_error($answer): array
{
    return is_wp_error($answer) ? ['ok' => false, 'error' => $answer->get_error_code()] : ['ok' => true === $answer];
}

/**
 * Operations on users, roles and options. Each reports what WordPress answered: a function that returns true or a
 * WP_Error is reported as such; one that returns nothing is reported from the state read back afterwards.
 */
function qp_capability_op(array $op, array $created, array $users): array
{
    switch ($op['op']) {
        case 'add_user':
            return qp_on_site('add_user', $op, $created, $users, static function ($site, $user) use ($op) {
                return qp_true_or_error(add_user_to_blog($site, $user, $op['role']));
            });
        case 'remove_user':
            return qp_on_site('remove_user', $op, $created, $users, static function ($site, $user) {
                return qp_true_or_error(remove_user_from_blog($user, $site));
            });
        case 'set_role':
            return qp_on_site('set_role', $op, $created, $users, static function ($site, $user) use ($op) {
                (new WP_User($user))->set_role($op['role']);
                $roles = (new WP_User($user))->roles;
                return ['ok' => '' === $op['role'] ? [] === $roles : [$op['role']] === array_values($roles)];
            });
        case 'add_cap':
            return qp_on_site('add_cap', $op, $created, $users, static function ($site, $user) use ($op) {
                (new WP_User($user))->add_cap($op['capability'], $op['grant']);
                $caps = (new WP_User($user))->caps;
                return ['ok' => array_key_exists($op['capability'], $caps) && $op['grant'] === $caps[$op['capability']]];
            });
        case 'super_admin':
            $user = $users[$op['user']] ?? null;
            if (null === $user) {
                return ['op' => 'super_admin', 'missing' => true];
            }
            return ['op' => 'super_admin', 'changed' => $op['grant'] ? grant_super_admin($user) : revoke_super_admin($user)];
        case 'network_flag':
            if ('add_new_users' === $op['name']) {
                $value = $op['value'] ? '1' : '0';
                update_site_option('add_new_users', $value);
                $stored = (string) get_site_option('add_new_users');
                return ['op' => 'network_flag', 'ok' => $value === $stored];
            }
            $value = $op['value'] ? ['plugins' => '1'] : [];
            update_site_option('menu_items', $value);
            return ['op' => 'network_flag', 'ok' => $value === get_site_option('menu_items')];
        case 'site_setting':
            $site = qp_site_id($op['site'], $created);
            if (null === $site) {
                return ['op' => 'site_setting', 'missing' => true];
            }
            $value = $op['value'] ? 1 : 0;
            update_blog_option($site, $op['name'], $value);
            return ['op' => 'site_setting', 'ok' => $value === (int) get_blog_option($site, $op['name'])];
        case 'add_role':
            return qp_on_site('add_role', $op + ['user' => 0], $created, $users, static function () use ($op) {
                // WordPress takes a map of capability => granted.
                $capabilities = array_fill_keys($op['capabilities'], true);
                return ['changed' => null !== add_role($op['role'], $op['name'], $capabilities)];
            });
        case 'role_cap':
            return qp_on_site('role_cap', $op + ['user' => 0], $created, $users, static function () use ($op) {
                $role = get_role($op['role']);
                if (null === $role) {
                    return ['ok' => false];
                }
                $role->add_cap($op['capability'], $op['grant']);
                $capabilities = get_role($op['role'])->capabilities;
                return ['ok' => array_key_exists($op['capability'], $capabilities)
                    && $op['grant'] === $capabilities[$op['capability']]];
            });
        case 'remove_role':
            return qp_on_site('remove_role', $op + ['user' => 0], $created, $users, static function () use ($op) {
                remove_role($op['role']);
                return ['ok' => null === get_role($op['role'])];
            });
        case 'can':
            $target = 0 === $op['target'] ? null : ($users[$op['target']] ?? null);
            if (0 !== $op['target'] && null === $target) {
                return ['op' => 'can', 'missing' => true];
            }
            return qp_on_site('can', $op, $created, $users, static function ($site, $user) use ($op, $target) {
                $args = null === $target ? [] : [$target];
                if (0 === $user) {
                    // A logged-out visitor is WordPress's current user 0, evaluated by current_user_can.
                    wp_set_current_user(0);
                    return ['can' => current_user_can($op['capability'], ...$args)];
                }
                return ['can' => user_can($user, $op['capability'], ...$args)];
            });
        default:
            throw new InvalidArgumentException('unknown op ' . $op['op']);
    }
}

/**
 * Acts as a user (0: nobody) for the next WordPress calls. Setting the current user fires set_current_user, on which
 * WordPress itself re-initializes its content filters (kses_init, wp_custom_css_kses_init) for that user.
 */
function qp_act_as(int $user): void
{
    wp_set_current_user($user);
}

/** The postarr of scenario fields, or null when a reference names a post or user that was never created. */
function qp_postarr(array $fields, array $users, array $posts): ?array
{
    $columns = ['type' => 'post_type', 'status' => 'post_status', 'title' => 'post_title',
        'content' => 'post_content', 'excerpt' => 'post_excerpt', 'slug' => 'post_name',
        'password' => 'post_password', 'comment_status' => 'comment_status', 'ping_status' => 'ping_status',
        'date' => 'post_date', 'date_gmt' => 'post_date_gmt', 'menu_order' => 'menu_order'];
    $postarr = [];
    foreach ($columns as $key => $column) {
        if (array_key_exists($key, $fields)) {
            $postarr[$column] = $fields[$key];
        }
    }
    if (array_key_exists('parent', $fields)) {
        $parent = 0 === $fields['parent'] ? 0 : ($posts[$fields['parent']] ?? null);
        if (null === $parent) {
            return null;
        }
        $postarr['post_parent'] = $parent;
    }
    if (array_key_exists('author', $fields)) {
        $author = 0 === $fields['author'] ? 0 : ($users[$fields['author']] ?? null);
        if (null === $author) {
            return null;
        }
        $postarr['post_author'] = $author;
    }
    return $postarr;
}

/** A post as the comparison reads it, or {deleted: true}. */
function qp_post_record(int $id): array
{
    clean_post_cache($id);
    $post = get_post($id);
    if (null === $post) {
        return ['deleted' => true];
    }
    $meta = [];
    foreach (['_wp_desired_post_slug', '_wp_old_date', '_wp_old_slug', '_wp_trash_meta_status',
                 '_wp_trash_meta_time'] as $key) {
        $values = get_post_meta($id, $key);
        if ([] !== $values) {
            $meta[$key] = array_map('strval', $values);
        }
    }
    return [
        'id' => $id, 'type' => $post->post_type, 'status' => $post->post_status, 'slug' => $post->post_name,
        'title' => $post->post_title, 'content' => $post->post_content, 'excerpt' => $post->post_excerpt,
        'author' => (int) $post->post_author, 'parent' => (int) $post->post_parent,
        'menu_order' => (int) $post->menu_order, 'date' => $post->post_date, 'date_gmt' => $post->post_date_gmt,
        'modified' => $post->post_modified, 'modified_gmt' => $post->post_modified_gmt,
        'comment_status' => $post->comment_status, 'ping_status' => $post->ping_status,
        'password' => $post->post_password, 'sticky' => is_sticky($id), 'guid' => $post->guid,
        'meta' => (object) $meta,
    ];
}

/** WordPress's answer of trash, untrash and delete: the post before (its status), or nothing (false or null). */
function qp_post_answer($answer): string
{
    return $answer instanceof WP_Post ? 'done:' . $answer->post_status : 'nothing';
}

/** WP_Query arguments of scenario query args; null when a reference names a post or user that was never created. */
function qp_query_args(array $args, array $users, array $posts): ?array
{
    $refs = static function (array $indexes, array $table): ?array {
        $ids = [];
        foreach ($indexes as $index) {
            if (null === ($table[$index] ?? null)) {
                return null;
            }
            $ids[] = $table[$index];
        }
        return $ids;
    };
    $query = ['ignore_sticky_posts' => !($args['sticky_first'] ?? false)];
    foreach (['types' => 'post_type', 'statuses' => 'post_status'] as $key => $name) {
        if (isset($args[$key])) {
            $query[$name] = $args[$key];
        }
    }
    foreach (['author_in' => ['author__in', $users], 'author_not_in' => ['author__not_in', $users],
                 'post_in' => ['post__in', $posts], 'post_not_in' => ['post__not_in', $posts]] as $key => [$name, $table]) {
        if (isset($args[$key])) {
            $ids = $refs($args[$key], $table);
            if (null === $ids) {
                return null;
            }
            $query[$name] = $ids;
        }
    }
    if (array_key_exists('parent', $args)) {
        $parent = 0 === $args['parent'] ? 0 : ($posts[$args['parent']] ?? null);
        if (null === $parent) {
            return null;
        }
        $query['post_parent'] = $parent;
    }
    if (isset($args['name'])) {
        $query['name'] = $args['name'];
    }
    if (isset($args['search'])) {
        // Driver-applied rule: query variables arrive slashed, as WordPress's request parsing leaves them.
        $query['s'] = wp_slash($args['search']);
    }
    if (isset($args['orderby'])) {
        // The scenario's order keys in WP_Query's names; "none" is only ever the sole order.
        $keys = ['date' => 'date', 'modified' => 'modified', 'title' => 'title', 'name' => 'name',
            'author' => 'author', 'parent' => 'parent', 'type' => 'type', 'id' => 'ID',
            'menu_order' => 'menu_order', 'post_in' => 'post__in'];
        if (['none asc'] === $args['orderby'] || ['none desc'] === $args['orderby']) {
            $query['orderby'] = 'none';
        } else {
            $query['orderby'] = [];
            foreach ($args['orderby'] as $order) {
                [$key, $direction] = explode(' ', $order);
                $query['orderby'][$keys[$key]] = strtoupper($direction);
            }
        }
    }
    foreach (['per_page' => 'posts_per_page', 'page' => 'paged', 'offset' => 'offset'] as $key => $name) {
        if (isset($args[$key])) {
            $query[$name] = $args[$key];
        }
    }
    return $query;
}

/**
 * The direction in which QuarkPress breaks ties between equal rows of a query: the first order key's, newest first
 * without one; null when nothing is appended (no order, or the post_in order, which has no ties).
 */
function qp_tie_direction(array $args): ?string
{
    $orderby = $args['orderby'] ?? [];
    if ([] === $orderby) {
        return 'DESC';
    }
    [$key, $direction] = explode(' ', $orderby[0]);
    return 'none' === $key || 'post_in' === $key ? null : strtoupper($direction);
}

/**
 * A post operation on the main site, as the op's user ("as"; 0 is WordPress's user 0). Each reports what WordPress
 * answered; a reference to a post or user that was never created is reported as missing.
 */
function qp_post_op(array $op, array $users, array &$posts): array
{
    $user = static fn(int $index): ?int => 0 === $index ? 0 : ($users[$index] ?? null);
    $post = static fn(int $index): ?int => $posts[$index] ?? null;
    switch_to_blog(get_main_site_id());
    try {
        switch ($op['op']) {
            case 'site_option':
                // update_option answers whether the stored value changed: false for the value already stored, and
                // for a value its sanitizing refuses (such as an unknown time zone), which keeps the old one.
                $changed = update_option($op['name'], $op['value']);
                if ('permalink_structure' === $op['name']) {
                    // Driver-applied rule: every operation stands for a request of its own, and a request's bootstrap
                    // reads the permalink structure into WP_Rewrite; this one read it before the change.
                    global $wp_rewrite;
                    $wp_rewrite->init();
                }
                return ['op' => 'site_option', 'changed' => $changed];
            case 'front_page':
                $id = 0 === $op['post'] ? 0 : $post($op['post']);
                if (null === $id) {
                    return ['op' => 'front_page', 'missing' => true];
                }
                return ['op' => 'front_page', 'changed' => update_option($op['option'], $id)];
            case 'insert_post':
                $as = $user($op['as']);
                $postarr = qp_postarr($op['fields'], $users, $posts);
                $index = count($posts) + 1;
                if (null === $as || null === $postarr) {
                    $posts[$index] = null;
                    return ['op' => 'insert_post', 'missing' => true];
                }
                qp_act_as($as);
                // wp_insert_post expects slashed data, as the REST layer passes it.
                $id = wp_insert_post(wp_slash($postarr), true);
                if (is_wp_error($id)) {
                    $posts[$index] = null;
                    return ['op' => 'insert_post', 'error' => $id->get_error_code()];
                }
                $posts[$index] = (int) $id;
                return ['op' => 'insert_post', 'id' => (int) $id];
            case 'update_post':
                $as = $user($op['as']);
                $id = $post($op['post']);
                $postarr = qp_postarr($op['fields'], $users, $posts);
                if (null === $as || null === $id || null === $postarr) {
                    return ['op' => 'update_post', 'missing' => true];
                }
                qp_act_as($as);
                $postarr['ID'] = $id;
                if ($op['edit_date']) {
                    $postarr['edit_date'] = true;
                }
                $result = wp_update_post(wp_slash($postarr), true);
                return is_wp_error($result)
                    ? ['op' => 'update_post', 'error' => $result->get_error_code()]
                    : ['op' => 'update_post', 'ok' => $id === (int) $result];
            case 'publish_post':
                $id = $post($op['post']);
                if (null === $id) {
                    return ['op' => 'publish_post', 'missing' => true];
                }
                // wp_publish_post answers nothing; it returns early for a post that does not exist or is published.
                wp_publish_post($id);
                return ['op' => 'publish_post', 'ok' => true];
            case 'trash_post':
            case 'untrash_post':
            case 'delete_post':
                $as = $user($op['as']);
                $id = $post($op['post']);
                if (null === $as || null === $id) {
                    return ['op' => $op['op'], 'missing' => true];
                }
                qp_act_as($as);
                $answer = match ($op['op']) {
                    'trash_post' => wp_trash_post($id),
                    'untrash_post' => wp_untrash_post($id),
                    'delete_post' => wp_delete_post($id, (bool) $op['force']),
                };
                return ['op' => $op['op'], 'result' => qp_post_answer($answer)];
            case 'stick':
                $id = $post($op['post']);
                if (null === $id) {
                    return ['op' => 'stick', 'missing' => true];
                }
                // stick_post and unstick_post answer nothing; they only edit the sticky_posts list, for any id.
                $op['sticky'] ? stick_post($id) : unstick_post($id);
                return ['op' => 'stick', 'ok' => true];
            case 'query_posts':
                $as = $user($op['as']);
                $args = qp_query_args($op['args'], $users, $posts);
                if (null === $as || null === $args) {
                    return ['op' => 'query_posts', 'missing' => true];
                }
                qp_act_as($as);
                // Driver-applied rule: WordPress leaves rows that tie on every order key to the database, which may
                // return them in any order; the driver appends the id in the direction QuarkPress breaks ties in.
                // For the query itself that is the first key's direction (qp_tie_direction). The only other query
                // WP_Query runs here is its fetch of sticky posts missing from the first page, ordered by
                // post_date DESC: QuarkPress orders those by date, then id, both descending, so it gets ID DESC.
                $tie = qp_tie_direction($op['args']);
                $query = new WP_Query();
                $total = static function (string $orderby, WP_Query $current) use ($tie, $query): string {
                    global $wpdb;
                    $direction = $current === $query ? $tie : 'DESC';
                    return '' === $orderby || null === $direction ? $orderby : "$orderby, {$wpdb->posts}.ID $direction";
                };
                add_filter('posts_orderby', $total, 10, 2);
                try {
                    $query->query($args);
                } finally {
                    remove_filter('posts_orderby', $total, 10);
                }
                return ['op' => 'query_posts', 'ids' => array_map(static fn($p) => (int) $p->ID, $query->posts),
                    'found' => (int) $query->found_posts];
            case 'page_uri':
                $id = $post($op['post']);
                if (null === $id) {
                    return ['op' => 'page_uri', 'missing' => true];
                }
                $uri = get_page_uri($id);
                return ['op' => 'page_uri', 'uri' => false === $uri ? '(false)' : $uri];
            case 'read_post':
                $id = $post($op['post']);
                return null === $id
                    ? ['op' => 'read_post', 'missing' => true]
                    : ['op' => 'read_post', 'post' => qp_post_record($id)];
            case 'update_post_meta':
                $as = $user($op['as']);
                $id = $post($op['post']);
                if (null === $as || null === $id) {
                    return ['op' => 'update_post_meta', 'missing' => true];
                }
                qp_act_as($as);
                // update_post_meta expects slashed data; it answers false when the value is unchanged, else the
                // meta id of a new row or true. Driver-applied rule: any answer but false counts as changed.
                return ['op' => 'update_post_meta',
                    'changed' => false !== update_post_meta($id, $op['key'], wp_slash($op['value']))];
            default:
                throw new InvalidArgumentException('unknown op ' . $op['op']);
        }
    } finally {
        restore_current_blog();
        qp_act_as(0);
    }
}

/**
 * Driver-applied rule: the custom hierarchical taxonomy genre, on post, with the default term General, registered as a
 * plugin registers its taxonomies (QuarkPress registers the same taxonomy with @Taxonomy). A plugin registers it on
 * every request; the driver registers it once per run, before the first operation. The two differ only when General
 * loses both its name and its slug (the next request would make a new default term), which scenarios do not do.
 */
function qp_register_genre(): void
{
    register_taxonomy('genre', 'post', ['hierarchical' => true, 'default_term' => 'General', 'show_in_rest' => true]);
}

/** The ids of the main site's terms created after $baseline, in creation order; deleted ones stay listed. */
function qp_track_terms(int $baseline, array &$created): void
{
    global $wpdb;
    $ids = $wpdb->get_col($wpdb->prepare("SELECT term_id FROM {$wpdb->terms} WHERE term_id > %d ORDER BY term_id",
        $baseline));
    foreach ($ids as $id) {
        if (!in_array((int) $id, $created, true)) {
            $created[] = (int) $id;
        }
    }
}

/** The default terms by taxonomy: the default category and genre's default term. */
function qp_term_defaults(): array
{
    return ['category' => (int) get_option('default_category'), 'genre' => (int) get_option('default_term_genre')];
}

/** A term reference as an id (0 for none), or null when it names a term that does not exist (yet). */
function qp_term_id(string $ref, string $taxonomy, array $created): ?int
{
    if ('none' === $ref) {
        return 0;
    }
    if ('default' === $ref) {
        $id = qp_term_defaults()[$taxonomy] ?? 0;
        return 0 === $id ? null : $id;
    }
    return $created[((int) substr($ref, 1)) - 1] ?? null;
}

/** A term as the comparison reads it, or {deleted: true}. */
function qp_term_record(int $id): array
{
    clean_term_cache($id);
    $term = get_term($id);
    return $term instanceof WP_Term ? qp_term_fields($term) : ['deleted' => true];
}

/** The fields of a term WordPress returned, as the comparison reads them. */
function qp_term_fields(WP_Term $term): array
{
    return ['id' => (int) $term->term_id, 'taxonomy' => $term->taxonomy, 'name' => $term->name,
        'slug' => $term->slug, 'description' => $term->description, 'parent' => (int) $term->parent,
        'count' => (int) $term->count];
}

/** wp_insert_term / wp_update_term arguments of scenario fields (slashed, as the REST layer passes them). */
function qp_term_args(array $fields, string $taxonomy, array $created): ?array
{
    $args = [];
    foreach (['name', 'slug', 'description'] as $key) {
        if (array_key_exists($key, $fields)) {
            $args[$key] = wp_slash($fields[$key]);
        }
    }
    if (array_key_exists('parent', $fields)) {
        $parent = qp_term_id($fields['parent'], $taxonomy, $created);
        if (null === $parent) {
            return null;
        }
        $args['parent'] = $parent;
    }
    return $args;
}

/** Assignment items: an id (an integer) for a reference, slashed text for a name; null for a missing reference. */
function qp_term_items(array $items, string $taxonomy, array $created): ?array
{
    $terms = [];
    foreach ($items as $item) {
        if (array_key_exists('term', $item)) {
            $id = qp_term_id($item['term'], $taxonomy, $created);
            if (null === $id) {
                return null;
            }
            $terms[] = $id;
        } else {
            $terms[] = wp_slash($item['name']);
        }
    }
    return $terms;
}

/**
 * Driver-applied rule: WordPress leaves terms that tie on the order key to the database; the driver appends the term
 * id in the query's direction, as QuarkPress breaks ties. Queries without order are compared as sets.
 */
function qp_term_tie(array $clauses): array
{
    if ('' !== trim($clauses['orderby']) && '' !== trim($clauses['order'])) {
        $clauses['order'] = $clauses['order'] . ', t.term_id ' . $clauses['order'];
    }
    return $clauses;
}

/** Runs the term list a query_terms or post_terms op compares with qp_term_tie applied, and only that. */
function qp_with_term_tie(callable $query)
{
    add_filter('terms_clauses', 'qp_term_tie');
    try {
        return $query();
    } finally {
        remove_filter('terms_clauses', 'qp_term_tie');
    }
}

/** get_terms arguments of scenario query args; null when a reference names a term or post that does not exist. */
function qp_term_query_args(array $args, array $posts, array $created): ?array
{
    $taxonomy = $args['taxonomies'][0] ?? 'category';
    $query = ['fields' => 'all'];
    if (isset($args['taxonomies'])) {
        $query['taxonomy'] = $args['taxonomies'];
    }
    foreach (['orderby' => 'orderby', 'order' => 'order', 'search' => 'search', 'name_like' => 'name__like',
                 'description_like' => 'description__like', 'number' => 'number', 'offset' => 'offset',
                 'hide_empty' => 'hide_empty', 'hierarchical' => 'hierarchical', 'pad_counts' => 'pad_counts',
                 'childless' => 'childless', 'slugs' => 'slug'] as $key => $name) {
        if (array_key_exists($key, $args)) {
            $query[$name] = $args[$key];
        }
    }
    if (!empty($args['get_all'])) {
        $query['get'] = 'all';
    }
    if (isset($args['names'])) {
        $query['name'] = array_map('wp_slash', $args['names']);
    }
    foreach (['include' => 'include', 'exclude' => 'exclude', 'exclude_tree' => 'exclude_tree'] as $key => $name) {
        if (isset($args[$key])) {
            $ids = [];
            foreach ($args[$key] as $ref) {
                $id = qp_term_id($ref, $taxonomy, $created);
                if (null === $id) {
                    return null;
                }
                $ids[] = $id;
            }
            $query[$name] = $ids;
        }
    }
    foreach (['parent' => 'parent', 'child_of' => 'child_of'] as $key => $name) {
        if (isset($args[$key])) {
            $id = qp_term_id($args[$key], $taxonomy, $created);
            if (null === $id) {
                return null;
            }
            $query[$name] = $id;
        }
    }
    if (isset($args['posts'])) {
        $ids = [];
        foreach ($args['posts'] as $index) {
            if (null === ($posts[$index] ?? null)) {
                return null;
            }
            $ids[] = $posts[$index];
        }
        $query['object_ids'] = $ids;
    }
    return $query;
}

/** The ids, and counts, of a list of terms. */
function qp_term_list(array $terms): array
{
    return ['ids' => array_map(static fn($t) => (int) $t->term_id, array_values($terms)),
        'counts' => array_map(static fn($t) => (int) $t->count, array_values($terms))];
}

/** A term operation on the main site, as the op's user ("as"; 0 is WordPress's user 0). */
function qp_term_op(array $op, array $users, array $posts, array &$created): array
{
    $user = static fn(int $index): ?int => 0 === $index ? 0 : ($users[$index] ?? null);
    $kind = $op['op'];
    switch_to_blog(get_main_site_id());
    try {
        $as = $user($op['as'] ?? 0);
        if (null === $as) {
            return ['op' => $kind, 'missing' => true];
        }
        qp_act_as($as);
        switch ($kind) {
            case 'insert_term':
                $args = qp_term_args($op['fields'], $op['taxonomy'], $created);
                if (null === $args) {
                    return ['op' => $kind, 'missing' => true];
                }
                $name = $args['name'];
                unset($args['name']);
                $result = wp_insert_term($name, $op['taxonomy'], $args);
                return is_wp_error($result)
                    ? ['op' => $kind, 'error' => $result->get_error_code()]
                    : ['op' => $kind, 'id' => (int) $result['term_id']];
            case 'update_term':
                $id = qp_term_id($op['term'], $op['taxonomy'], $created);
                $args = qp_term_args($op['fields'], $op['taxonomy'], $created);
                if (null === $id || null === $args) {
                    return ['op' => $kind, 'missing' => true];
                }
                $result = wp_update_term($id, $op['taxonomy'], $args);
                return is_wp_error($result)
                    ? ['op' => $kind, 'error' => $result->get_error_code()]
                    : ['op' => $kind, 'ok' => true];
            case 'delete_term':
                $id = qp_term_id($op['term'], $op['taxonomy'], $created);
                if (null === $id) {
                    return ['op' => $kind, 'missing' => true];
                }
                $result = wp_delete_term($id, $op['taxonomy']);
                if (is_wp_error($result)) {
                    return ['op' => $kind, 'error' => $result->get_error_code()];
                }
                return ['op' => $kind, 'result' => true === $result ? 'deleted' : (0 === $result ? 'kept' : 'nothing')];
            case 'set_post_terms':
            case 'remove_post_terms':
                $post = $posts[$op['post']] ?? null;
                $terms = qp_term_items($op['terms'], $op['taxonomy'], $created);
                if (null === $post || null === $terms) {
                    return ['op' => $kind, 'missing' => true];
                }
                if ('remove_post_terms' === $kind) {
                    $removed = wp_remove_object_terms($post, $terms, $op['taxonomy']);
                    return is_wp_error($removed)
                        ? ['op' => $kind, 'error' => $removed->get_error_code()]
                        : ['op' => $kind, 'removed' => true === $removed];
                }
                $tt_ids = wp_set_object_terms($post, $terms, $op['taxonomy'], (bool) $op['append']);
                if (is_wp_error($tt_ids)) {
                    return ['op' => $kind, 'error' => $tt_ids->get_error_code()];
                }
                $ids = [];
                foreach ($tt_ids as $tt_id) {
                    $ids[] = (int) get_term_by('term_taxonomy_id', (int) $tt_id)->term_id;
                }
                return ['op' => $kind, 'ids' => $ids];
            case 'post_terms':
                $post = $posts[$op['post']] ?? null;
                if (null === $post) {
                    return ['op' => $kind, 'missing' => true];
                }
                $terms = qp_with_term_tie(static fn() => wp_get_object_terms($post, $op['taxonomy']));
                return is_wp_error($terms)
                    ? ['op' => $kind, 'error' => $terms->get_error_code()]
                    : ['op' => $kind] + qp_term_list($terms);
            case 'query_terms':
                $args = qp_term_query_args($op['args'], $posts, $created);
                if (null === $args) {
                    return ['op' => $kind, 'missing' => true];
                }
                $terms = qp_with_term_tie(static fn() => get_terms($args));
                return is_wp_error($terms)
                    ? ['op' => $kind, 'error' => $terms->get_error_code()]
                    : ['op' => $kind] + qp_term_list($terms);
            case 'get_term':
                $taxonomy = '' === $op['taxonomy'] ? 'category' : $op['taxonomy'];
                $id = qp_term_id($op['term'], $taxonomy, $created);
                if (null === $id) {
                    return ['op' => $kind, 'missing' => true];
                }
                $term = '' === $op['taxonomy'] ? get_term($id) : get_term($id, $op['taxonomy']);
                if (is_wp_error($term)) {
                    return ['op' => $kind, 'error' => $term->get_error_code()];
                }
                // What get_term returned, as it returned it: re-reading would clean the term caches mid-scenario.
                return ['op' => $kind, 'term' => $term instanceof WP_Term ? qp_term_fields($term) : null];
            case 'term_exists':
                $parent = null;
                if (isset($op['parent'])) {
                    $parent = qp_term_id($op['parent'], $op['taxonomy'], $created);
                    if (null === $parent) {
                        return ['op' => $kind, 'missing' => true];
                    }
                }
                $found = term_exists(wp_slash($op['value']), $op['taxonomy'], $parent);
                return ['op' => $kind, 'id' => is_array($found) ? (int) $found['term_id'] : 0];
            default:
                throw new InvalidArgumentException('unknown op ' . $kind);
        }
    } finally {
        restore_current_blog();
        qp_act_as(0);
    }
}

/** The ids of the main site's revision rows created after $baseline, in creation order; deleted ones stay listed. */
function qp_track_revisions(int $baseline, array &$created): void
{
    global $wpdb;
    $ids = $wpdb->get_col($wpdb->prepare(
        "SELECT ID FROM {$wpdb->posts} WHERE post_type = 'revision' AND ID > %d ORDER BY ID", $baseline));
    foreach ($ids as $id) {
        if (!in_array((int) $id, $created, true)) {
            $created[] = (int) $id;
        }
    }
}

/**
 * Driver-applied rule: a revision is named r<k>, the k-th revision row created on the site during the scenario
 * (qp_track_revisions); the reference is an id, or null when it names a row that does not exist (yet).
 */
function qp_revision_id(string $ref, array $created): ?int
{
    return $created[((int) substr($ref, 1)) - 1] ?? null;
}

/** A revision as the comparison reads it, or {deleted: true}. */
function qp_revision_record(int $id): array
{
    clean_post_cache($id);
    $revision = wp_get_post_revision($id);
    if (null === $revision) {
        return ['deleted' => true];
    }
    return ['id' => $id, 'parent' => (int) $revision->post_parent, 'author' => (int) $revision->post_author,
        'autosave' => false !== wp_is_post_autosave($revision), 'title' => $revision->post_title,
        'content' => $revision->post_content, 'excerpt' => $revision->post_excerpt,
        // get_post_meta reads the revision's own rows (it is the write functions that redirect to the parent)
        'footnotes' => array_map('strval', get_post_meta($id, 'footnotes')), 'date_gmt' => $revision->post_date_gmt];
}

/**
 * Driver-applied rule: the REST layer's permission refusals (rest_cannot_edit, rest_cannot_read, rest_forbidden,
 * rest_cannot_edit_others) are one answer, forbidden, as QuarkPress's capability refusals are; other codes pass.
 */
function qp_rest_code(WP_Error $error): string
{
    $code = $error->get_error_code();
    return in_array($code, ['rest_cannot_edit', 'rest_cannot_read', 'rest_forbidden', 'rest_cannot_edit_others'], true)
        ? 'forbidden' : $code;
}

/**
 * Driver-applied rule: a post that does not exist (it was deleted) is invalid_post, the answer
 * wp_get_latest_revision_id_and_total_count gives, before any capability check: current_user_can('edit_post') of a
 * deleted post would answer do_not_allow, which would read as forbidden.
 */
function qp_deleted_post(int $post): ?array
{
    return null === get_post($post) ? ['error' => 'invalid_post'] : null;
}

/** Driver-applied rule: the REST revisions and autosaves lists need edit_post on the post for a logged-in user. */
function qp_may_read_revisions(int $user, int $post): bool
{
    return 0 === $user || current_user_can('edit_post', $post);
}

function qp_revision_op(array $op, array $users, array $posts, array &$created): array
{
    $user = static fn(int $index): ?int => 0 === $index ? 0 : ($users[$index] ?? null);
    $post = static fn(int $index): ?int => $posts[$index] ?? null;
    switch_to_blog(get_main_site_id());
    try {
        switch ($op['op']) {
            case 'autosave':
                $as = $user($op['as']);
                $id = $post($op['post']);
                if (null === $as || null === $id) {
                    return ['op' => 'autosave', 'missing' => true];
                }
                qp_act_as($as);
                $gone = qp_deleted_post($id);
                if (null !== $gone) {
                    return ['op' => 'autosave'] + $gone;
                }
                // The REST autosaves endpoint is called as a request would call it, through the post type's
                // autosaves controller, with the fields and meta the request would carry.
                rest_get_server();
                $controller = get_post_type_object(get_post_type($id))->get_autosave_rest_controller();
                $request = new WP_REST_Request('POST', '/wp/v2/posts/' . $id . '/autosaves');
                $request->set_param('id', $id);
                foreach (['title', 'content', 'excerpt'] as $field) {
                    if (array_key_exists($field, $op['fields'])) {
                        $request->set_param($field, $op['fields'][$field]);
                    }
                }
                if (array_key_exists('meta', $op)) {
                    $request->set_param('meta', (object) $op['meta']);
                }
                $allowed = $controller->create_item_permissions_check($request);
                if (is_wp_error($allowed)) {
                    return ['op' => 'autosave', 'error' => qp_rest_code($allowed)];
                }
                // Driver-applied rule: what DOING_AUTOSAVE silences for one request. With WP_RUN_CORE_TESTS
                // defined the controller never defines that constant, so an author's own draft, which the
                // controller saves with wp_update_post, would save a regular revision through post_updated; a
                // real request is an autosave and saves none, so the hook is off for this call only.
                remove_action('post_updated', 'wp_save_post_revision');
                try {
                    $response = $controller->create_item($request);
                } finally {
                    add_action('post_updated', 'wp_save_post_revision', 10, 1);
                }
                if (is_wp_error($response)) {
                    return ['op' => 'autosave', 'error' => qp_rest_code($response)];
                }
                $data = $response->get_data();
                return ['op' => 'autosave', 'id' => (int) $data['id'], 'parent' => (int) $data['parent']];
            case 'restore':
                $as = $user($op['as']);
                $id = qp_revision_id($op['revision'], $created);
                if (null === $as || null === $id) {
                    return ['op' => 'restore', 'missing' => true];
                }
                qp_act_as($as);
                $revision = wp_get_post_revision($id);
                if (null === $revision) {
                    return ['op' => 'restore', 'result' => 'nothing'];
                }
                // Driver-applied rule: the revisions screen's checks before wp_restore_post_revision: edit_post on
                // the parent, and revisions enabled for it unless the revision is an autosave (no lock: scenarios
                // set none).
                if (!current_user_can('edit_post', $revision->post_parent)) {
                    return ['op' => 'restore', 'error' => 'forbidden'];
                }
                if (!wp_revisions_enabled(get_post($revision->post_parent))
                    && false === wp_is_post_autosave($revision)) {
                    return ['op' => 'restore', 'error' => 'revisions_not_enabled'];
                }
                $fields = [];
                foreach ($op['fields'] as $field) {
                    $fields[] = 'post_' . $field;
                }
                $result = wp_restore_post_revision($id, [] === $fields ? null : $fields);
                return $result ? ['op' => 'restore', 'ok' => true] : ['op' => 'restore', 'result' => 'nothing'];
            case 'delete_revision':
                $id = qp_revision_id($op['revision'], $created);
                if (null === $id) {
                    return ['op' => 'delete_revision', 'missing' => true];
                }
                qp_act_as(0);
                $deleted = wp_delete_post_revision($id);
                return ['op' => 'delete_revision', 'result' => $deleted ? 'deleted' : 'nothing'];
            case 'revisions':
                $as = $user($op['as']);
                $id = $post($op['post']);
                if (null === $as || null === $id) {
                    return ['op' => 'revisions', 'missing' => true];
                }
                qp_act_as($as);
                $gone = qp_deleted_post($id);
                if (null !== $gone) {
                    return ['op' => 'revisions'] + $gone;
                }
                if (!qp_may_read_revisions($as, $id)) {
                    return ['op' => 'revisions', 'error' => 'forbidden'];
                }
                $args = [];
                if (array_key_exists('order', $op['args'])) {
                    $args['order'] = strtoupper($op['args']['order']);
                }
                if (array_key_exists('number', $op['args'])) {
                    $args['numberposts'] = $op['args']['number'];
                }
                if (array_key_exists('offset', $op['args'])) {
                    $args['offset'] = $op['args']['offset'];
                }
                if (array_key_exists('author', $op['args'])) {
                    $author = $user($op['args']['author']);
                    if (null === $author) {
                        return ['op' => 'revisions', 'missing' => true];
                    }
                    $args['author'] = $author;
                }
                if (array_key_exists('check_enabled', $op['args'])) {
                    $args['check_enabled'] = $op['args']['check_enabled'];
                }
                $ids = [];
                foreach (wp_get_post_revisions($id, $args) as $revision) {
                    // Driver-applied rule: the autosaves argument keeps or drops autosaves as callers of
                    // wp_get_post_revisions do, by the revision's name.
                    $autosave = false !== wp_is_post_autosave($revision);
                    $mode = $op['args']['autosaves'] ?? 'include';
                    if (('only' === $mode && !$autosave) || ('exclude' === $mode && $autosave)) {
                        continue;
                    }
                    $ids[] = (int) $revision->ID;
                }
                return ['op' => 'revisions', 'ids' => $ids];
            case 'autosave_of':
                $as = $user($op['as']);
                $id = $post($op['post']);
                $of = $user($op['user']);
                if (null === $as || null === $id || null === $of) {
                    return ['op' => 'autosave_of', 'missing' => true];
                }
                qp_act_as($as);
                $gone = qp_deleted_post($id);
                if (null !== $gone) {
                    return ['op' => 'autosave_of'] + $gone;
                }
                if (!qp_may_read_revisions($as, $id)) {
                    return ['op' => 'autosave_of', 'error' => 'forbidden'];
                }
                $autosave = wp_get_post_autosave($id, $of);
                return ['op' => 'autosave_of', 'id' => $autosave instanceof WP_Post ? (int) $autosave->ID : 0];
            case 'revision_count':
                $as = $user($op['as']);
                $id = $post($op['post']);
                if (null === $as || null === $id) {
                    return ['op' => 'revision_count', 'missing' => true];
                }
                qp_act_as($as);
                $gone = qp_deleted_post($id);
                if (null !== $gone) {
                    return ['op' => 'revision_count'] + $gone;
                }
                if (!qp_may_read_revisions($as, $id)) {
                    return ['op' => 'revision_count', 'error' => 'forbidden'];
                }
                $result = wp_get_latest_revision_id_and_total_count($id);
                if (is_wp_error($result)) {
                    return ['op' => 'revision_count', 'error' => $result->get_error_code()];
                }
                return ['op' => 'revision_count', 'latest' => (int) $result['latest_id'],
                    'count' => (int) $result['count']];
            default:
                throw new InvalidArgumentException('unknown op ' . $op['op']);
        }
    } finally {
        restore_current_blog();
        qp_act_as(0);
    }
}

function qp_run_scenario(array $scenario, array $variant): array
{
    global $wpdb;
    $segments = (int) $variant['path_segments'];
    if ($segments > 1) {
        // QuarkPress's path-segments counts segments the way WordPress's own count of 1 does (after the network path
        // where WordPress adds it), so the filter adds the difference to every count WordPress computes.
        add_filter('site_by_path_segments_count', static fn($count) => null === $count ? null : $count + $segments - 1, 10, 1);
    }
    $created = [];
    $users = [];
    $posts = [];
    $term_ops = ['insert_term', 'update_term', 'delete_term', 'set_post_terms', 'remove_post_terms', 'post_terms',
        'query_terms', 'get_term', 'term_exists'];
    $qp_term_scenario = [] !== array_intersect(array_column($scenario['ops'], 'op'), $term_ops);
    // Driver-applied rule (qp_register_genre): every run registers genre, as QuarkPress always has the taxonomy.
    switch_to_blog(get_main_site_id());
    qp_register_genre();
    $term_baseline = (int) $wpdb->get_var("SELECT MAX(term_id) FROM {$wpdb->terms}");
    restore_current_blog();
    $created_terms = [];
    $revision_ops = ['autosave', 'restore', 'delete_revision', 'revisions', 'autosave_of', 'revision_count'];
    $qp_revision_scenario = [] !== array_intersect(array_column($scenario['ops'], 'op'),
        array_merge($revision_ops, ['update_post_meta']));
    switch_to_blog(get_main_site_id());
    $revision_baseline = (int) $wpdb->get_var("SELECT MAX(ID) FROM {$wpdb->posts}");
    restore_current_blog();
    $created_revisions = [];
    $started = time();
    $results = [];
    foreach ($scenario['ops'] as $op) {
        $results[] = match ($op['op']) {
            'create_site' => qp_create_site($op, $variant, $created),
            'lookup' => qp_lookup($op, $variant),
            'set_flag' => qp_set_flag($op, $created),
            'read_options' => qp_read_options($op, $created),
            'create_user' => qp_create_user($op, $users),
            'add_user', 'remove_user', 'set_role', 'add_cap', 'super_admin', 'network_flag', 'site_setting',
            'add_role', 'role_cap', 'remove_role', 'can' => qp_capability_op($op, $created, $users),
            'site_option', 'front_page', 'insert_post', 'update_post', 'publish_post', 'trash_post',
            'untrash_post', 'delete_post', 'stick', 'query_posts', 'page_uri', 'read_post', 'update_post_meta'
                => qp_post_op($op, $users, $posts),
            'autosave', 'restore', 'delete_revision', 'revisions', 'autosave_of', 'revision_count'
                => qp_revision_op($op, $users, $posts, $created_revisions),
            'insert_term', 'update_term', 'delete_term', 'set_post_terms', 'remove_post_terms', 'post_terms',
            'query_terms', 'get_term', 'term_exists' => qp_term_op($op, $users, $posts, $created_terms),
            default => throw new InvalidArgumentException('unknown op ' . $op['op']),
        };
        if ($qp_term_scenario) {
            // Terms are named by creation order, those an assignment creates by name included.
            switch_to_blog(get_main_site_id());
            qp_track_terms($term_baseline, $created_terms);
            restore_current_blog();
        }
        if ($qp_revision_scenario) {
            // Revisions are named by creation order: regular revisions and new autosaves alike.
            switch_to_blog(get_main_site_id());
            qp_track_revisions($revision_baseline, $created_revisions);
            restore_current_blog();
        }
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
    $records = [];
    switch_to_blog(get_main_site_id());
    foreach ($posts as $index => $id) {
        if (null !== $id) {
            $records[] = ['index' => $index, 'post' => qp_post_record($id)];
        }
    }
    $home = home_url();
    restore_current_blog();
    // The installation's user: wp_install created it with the login admin.
    $installer = get_user_by('login', 'admin');
    $out = ['main' => get_main_site_id(), 'results' => $results, 'sites' => $sites, 'posts' => $records,
        'started' => $started, 'finished' => time(), 'home' => $home,
        'installer' => false === $installer ? 0 : (int) $installer->ID];
    if ($qp_term_scenario) {
        switch_to_blog(get_main_site_id());
        $terms_out = [];
        foreach ($created_terms as $index => $id) {
            $terms_out[] = ['index' => $index + 1, 'id' => $id, 'term' => qp_term_record($id)];
        }
        $defaults_out = [];
        foreach (qp_term_defaults() as $taxonomy => $id) {
            $defaults_out[$taxonomy] = ['id' => $id, 'term' => qp_term_record($id)];
        }
        restore_current_blog();
        $out['terms'] = $terms_out;
        $out['term_defaults'] = $defaults_out;
    }
    if ($qp_revision_scenario) {
        switch_to_blog(get_main_site_id());
        $revisions_out = [];
        foreach ($created_revisions as $index => $id) {
            $revisions_out[] = ['index' => $index + 1, 'id' => $id, 'revision' => qp_revision_record($id)];
        }
        restore_current_blog();
        $out['revisions'] = $revisions_out;
    }
    return $out;
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
        // Driver-applied rule: the installation's sample content (a post, a page and the privacy policy draft, with
        // the sample comment) is deleted for good, so post scenarios start from an empty main site on both sides:
        // QuarkPress creates no sample content.
        foreach ($wpdb->get_col("SELECT ID FROM {$wpdb->posts}") as $qp_post) {
            wp_delete_post((int) $qp_post, true);
        }
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
        qp_prepare($qp_wordpress, $qp_input['database'], $qp_variant, true,
            $qp_input['scenario']['revisions_keep'] ?? null);
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
