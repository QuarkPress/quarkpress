<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Setup script of the REST parity site: runs one operation on the site's database, outside HTTP.
 *   php rest-setup.php <operation> <wordpress-dir> <input.json> <output.json>
 * The site's configuration comes from QP_REST_CONFIG (rest-config.php as WordPressRestSite wrote it), the database
 * server from QP_DB_HOST, QP_DB_PORT, QP_DB_USER and QP_DB_PASSWORD. The answer is {"out": ...} or {"error": ...}.
 * Operations:
 *   prepare  {template, database}  clones the template network into the site's database and sets it up
 *   option   {name, value}         update_option on the main site
 * The script calls WordPress functions; every rule it applies itself is marked "Harness rule" where it happens.
 */

declare(strict_types=1);

if (5 !== $argc) {
    fwrite(STDERR, "usage: php rest-setup.php <operation> <wordpress-dir> <input.json> <output.json>\n");
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
        throw new InvalidArgumentException("not a site database name: $name");
    }
    return '`' . $name . '`';
}

/** Copies every table of the template database, with its rows, into a new database. */
function qp_clone_database(string $template, string $target): void
{
    $server = qp_server();
    $server->query('CREATE DATABASE ' . qp_identifier($target) . ' CHARACTER SET utf8mb4');
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

/** No mail and no HTTP requests leave the site. */
function qp_offline(): void
{
    add_filter('pre_wp_mail', '__return_true');
    add_filter('pre_http_request', static fn() => new WP_Error('qp_offline', 'the REST setup script makes no HTTP requests'), 10, 0);
}

/** The dev realm's users: login (also the password), display name and role on the main site. */
const QP_REST_USERS = [
    'bob' => ['Bob Baker', 'editor'],
    'carol' => ['Carol Chen', 'author'],
    'dave' => ['Dave Diaz', 'contributor'],
    'erin' => ['Erin Evans', 'subscriber'],
    'alice' => ['Alice Anders', 'administrator'],
];

function qp_prepare_site(): array
{
    global $wp_rewrite;
    $main = get_main_site_id();
    switch_to_blog($main);
    update_option('permalink_structure', '/%postname%/');
    $wp_rewrite->init();
    flush_rewrite_rules();

    $ids = [];
    foreach (QP_REST_USERS as $login => [$display, $role]) {
        // Harness rule: the dev realm's user as WordPress stores it: e-mail and display name as the realm has them, no
        // first or last name, the login as password.
        $id = wp_insert_user([
            'user_login' => $login,
            'user_pass' => $login,
            'user_email' => $login . '@quarkpress.test',
            'display_name' => $display,
        ]);
        if (is_wp_error($id)) {
            throw new RuntimeException("wp_insert_user($login): " . $id->get_error_code());
        }
        $added = add_user_to_blog($main, (int) $id, $role);
        if (is_wp_error($added)) {
            throw new RuntimeException("add_user_to_blog($login): " . $added->get_error_code());
        }
        $ids[$login] = (int) $id;
    }
    if (!grant_super_admin($ids['alice'])) {
        throw new RuntimeException('grant_super_admin(alice) refused');
    }

    // Harness rule: the installer's admin is removed (after alice became super admin), so both sides have exactly the
    // five dev-realm users.
    $admin = get_user_by('login', 'admin');
    if (false === $admin) {
        throw new RuntimeException('the template network has no admin user');
    }
    if (!revoke_super_admin($admin->ID)) {
        throw new RuntimeException('revoke_super_admin(admin) refused');
    }
    require_once ABSPATH . 'wp-admin/includes/admin.php';
    if (!wpmu_delete_user($admin->ID)) {
        throw new RuntimeException('wpmu_delete_user(admin) refused');
    }

    $users = [];
    foreach (['alice', 'bob', 'carol', 'dave', 'erin'] as $login) {
        $created = WP_Application_Passwords::create_new_application_password($ids[$login], ['name' => 'parity']);
        if (is_wp_error($created)) {
            throw new RuntimeException("create_new_application_password($login): " . $created->get_error_code());
        }
        $users[$login] = ['id' => $ids[$login], 'password' => $created[0]];
    }
    restore_current_blog();
    return ['users' => $users];
}

function qp_option(array $input): array
{
    switch_to_blog(get_main_site_id());
    update_option($input['name'], $input['value']);
    $value = get_option($input['name']);
    restore_current_blog();
    return ['name' => $input['name'], 'value' => $value];
}

$qp_input = json_decode((string) file_get_contents($qp_input_file), true, 512, JSON_THROW_ON_ERROR);
try {
    if ('prepare' === $qp_mode) {
        qp_clone_database($qp_input['template'], $qp_input['database']);
    }
    define('ABSPATH', rtrim($qp_wordpress, '/') . '/');
    // What the web server would tell WordPress: a request for the main site's front page.
    $_SERVER['HTTP_HOST'] = 'localhost';
    $_SERVER['SERVER_NAME'] = 'localhost';
    $_SERVER['SERVER_PORT'] = '80';
    $_SERVER['REQUEST_METHOD'] = 'GET';
    $_SERVER['REQUEST_URI'] = '/';
    $_SERVER['SCRIPT_NAME'] = '/index.php';
    $_SERVER['PHP_SELF'] = '/index.php';
    require qp_env('QP_REST_CONFIG');
    qp_offline();
    $qp_out = match ($qp_mode) {
        'prepare' => qp_prepare_site(),
        'option' => qp_option($qp_input),
        default => throw new InvalidArgumentException("unknown operation $qp_mode"),
    };
    qp_write($qp_output_file, ['out' => $qp_out]);
} catch (Throwable $qp_failure) {
    qp_write($qp_output_file, ['error' => get_class($qp_failure) . ': ' . $qp_failure->getMessage()]);
    fwrite(STDERR, $qp_failure . "\n");
    exit(1);
}
