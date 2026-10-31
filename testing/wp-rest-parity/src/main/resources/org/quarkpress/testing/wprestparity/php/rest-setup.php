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
 *   option   {name, value, site?}  update_option on the main site, or on the site at path "site" (permalink_structure
 *                                  also rebuilds the rewrite rules)
 *   create_site {path, title}      creates a site on localhost at path with the five users, without sample content;
 *                                  answers {path, refs: {"t:uncategorized": id, "u:alice": id, ...}}
 *   post {site, as, type, status, title}  inserts a post on the site at path "site", written by "as"; answers {id}
 *   revoke_passwords {user}        deletes every application password of the user
 *   app_password {user}            creates an application password named "parity"; answers {password}
 *   archived_site {path}           creates a site on localhost at path and marks it archived; answers {id}
 *   outsider {login}               creates a network user who is a member of no site; answers {id}
 *   leave {site, user}             removes the user from the site at path "site" (no role there); answers {left}
 *   delete_user {login}            deletes a network user (wpmu_delete_user); answers {deleted}
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

/**
 * The dev realm's users: login (also the password), display name and role on the main site. Harness rule: created in
 * QuarkPress's order, so their ids sort the same on both sides: alice (the network's initial super admin, whose profile
 * QuarkPress makes at start) first, then the others in the order of their first login.
 */
const QP_REST_USERS = [
    'alice' => ['Alice Anders', 'administrator'],
    'bob' => ['Bob Baker', 'editor'],
    'carol' => ['Carol Chen', 'author'],
    'dave' => ['Dave Diaz', 'contributor'],
    'erin' => ['Erin Evans', 'subscriber'],
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
    global $wp_rewrite;
    switch_to_blog(isset($input['site']) ? qp_site_id((string) $input['site']) : get_main_site_id());
    update_option($input['name'], $input['value']);
    if ('permalink_structure' === $input['name']) {
        // Harness rule: what saving the permalink settings screen does, so the rewrite rules follow the structure.
        $wp_rewrite->init();
        flush_rewrite_rules();
    }
    $value = get_option($input['name']);
    restore_current_blog();
    return ['name' => $input['name'], 'value' => $value];
}

/** The id of the site at path on localhost. */
function qp_site_id(string $path): int
{
    $id = get_blog_id_from_url('localhost', $path);
    if (0 === $id) {
        throw new InvalidArgumentException("no site at $path");
    }
    return (int) $id;
}

function qp_create_site(array $input): array
{
    // Harness rule: alice, the super admin, creates the site and is its administrator.
    $alice = qp_user_id(['user' => 'alice']);
    $id = wpmu_create_blog('localhost', (string) $input['path'], (string) $input['title'], $alice);
    if (is_wp_error($id)) {
        throw new RuntimeException('wpmu_create_blog: ' . $id->get_error_code());
    }
    switch_to_blog($id);
    // Harness rule (driver-applied, not WordPress behaviour): QuarkPress creates no sample content, so everything the
    // installer wrote except the Uncategorized category is deleted for good: the sample post with its comment, the
    // sample page and the privacy-policy draft.
    global $wpdb;
    foreach ($wpdb->get_col("SELECT ID FROM $wpdb->posts") as $post) {
        if (!wp_delete_post((int) $post, true)) {
            throw new RuntimeException("wp_delete_post($post) refused");
        }
    }
    foreach ($wpdb->get_col("SELECT comment_ID FROM $wpdb->comments") as $comment) {
        if (!wp_delete_comment((int) $comment, true)) {
            throw new RuntimeException("wp_delete_comment($comment) refused");
        }
    }
    // Harness rule: the dev realm's users with their roles, as on the main site.
    foreach (QP_REST_USERS as $login => [, $role]) {
        if ('alice' === $login) {
            continue;
        }
        $added = add_user_to_blog($id, qp_user_id(['user' => $login]), $role);
        if (is_wp_error($added)) {
            throw new RuntimeException("add_user_to_blog($login): " . $added->get_error_code());
        }
    }
    $refs = ['t:uncategorized' => (int) get_option('default_category')];
    foreach (['alice', 'bob', 'carol', 'dave', 'erin'] as $login) {
        $refs['u:' . $login] = qp_user_id(['user' => $login]);
    }
    restore_current_blog();
    return ['path' => (string) $input['path'], 'refs' => $refs];
}

function qp_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $author = qp_user_id($input, 'as');
    wp_set_current_user($author);
    $id = wp_insert_post([
        'post_type' => (string) $input['type'],
        'post_status' => (string) $input['status'],
        'post_title' => (string) $input['title'],
        'post_author' => $author,
    ], true);
    wp_set_current_user(0);
    restore_current_blog();
    if (is_wp_error($id)) {
        throw new RuntimeException('wp_insert_post: ' . $id->get_error_code());
    }
    return ['id' => (int) $id];
}

function qp_user_id(array $input, string $key = 'user'): int
{
    $user = get_user_by('login', (string) $input[$key]);
    if (false === $user) {
        throw new InvalidArgumentException('no such user: ' . $input[$key]);
    }
    return $user->ID;
}

function qp_revoke_passwords(array $input): array
{
    $deleted = WP_Application_Passwords::delete_all_application_passwords(qp_user_id($input));
    if (is_wp_error($deleted)) {
        throw new RuntimeException('delete_all_application_passwords: ' . $deleted->get_error_code());
    }
    return ['deleted' => $deleted];
}

function qp_app_password(array $input): array
{
    $created = WP_Application_Passwords::create_new_application_password(qp_user_id($input), ['name' => 'parity']);
    if (is_wp_error($created)) {
        throw new RuntimeException('create_new_application_password: ' . $created->get_error_code());
    }
    return ['password' => $created[0]];
}

function qp_archived_site(array $input): array
{
    // Harness rule, not WordPress behaviour: a site that exists and is archived, for the 410 answer.
    // Harness rule: alice, the super admin, is the new site's administrator.
    $id = wpmu_create_blog('localhost', (string) $input['path'], 'Archived', qp_user_id(['user' => 'alice']));
    if (is_wp_error($id)) {
        throw new RuntimeException('wpmu_create_blog: ' . $id->get_error_code());
    }
    update_blog_status($id, 'archived', 1);
    return ['id' => (int) $id];
}

function qp_outsider(array $input): array
{
    // Harness rule: a network user with no role on any site.
    $id = wpmu_create_user((string) $input['login'], (string) $input['login'], $input['login'] . '@quarkpress.test');
    if (false === $id) {
        throw new RuntimeException('wpmu_create_user refused ' . $input['login']);
    }
    foreach (get_blogs_of_user($id, true) as $blog) {
        remove_user_from_blog($id, (int) $blog->userblog_id);
    }
    return ['id' => (int) $id];
}

function qp_leave(array $input): array
{
    // Harness rule: the user leaves the scenario's site (remove_user_from_blog) and stays a member of the main site.
    $removed = remove_user_from_blog(qp_user_id($input), qp_site_id((string) $input['site']));
    if (is_wp_error($removed)) {
        throw new RuntimeException('remove_user_from_blog: ' . $removed->get_error_code());
    }
    return ['left' => true];
}

function qp_delete_user(array $input): array
{
    require_once ABSPATH . 'wp-admin/includes/admin.php';
    $user = get_user_by('login', (string) $input['login']);
    return ['deleted' => false !== $user && wpmu_delete_user($user->ID)];
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
        'create_site' => qp_create_site($qp_input),
        'post' => qp_post($qp_input),
        'revoke_passwords' => qp_revoke_passwords($qp_input),
        'app_password' => qp_app_password($qp_input),
        'archived_site' => qp_archived_site($qp_input),
        'outsider' => qp_outsider($qp_input),
        'leave' => qp_leave($qp_input),
        'delete_user' => qp_delete_user($qp_input),
        default => throw new InvalidArgumentException("unknown operation $qp_mode"),
    };
    qp_write($qp_output_file, ['out' => $qp_out]);
} catch (Throwable $qp_failure) {
    qp_write($qp_output_file, ['error' => get_class($qp_failure) . ': ' . $qp_failure->getMessage()]);
    fwrite(STDERR, $qp_failure . "\n");
    exit(1);
}
