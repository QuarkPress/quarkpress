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
 *   create_site {path, title}      creates a site on localhost at path with the five users, without sample content,
 *                                  with /%postname%/ permalinks and the custom type book (qp_parity_book_type);
 *                                  answers {path, refs: {"t:uncategorized": id, "u:alice": id, ...}}
 *   post {site, as, type, status, title, fields?}  inserts a post on the site at path "site", written by "as"; answers
 *                                  {id}. "fields" (all optional) are wp_insert_post arguments post_content,
 *                                  post_excerpt, post_password, post_parent, post_date, post_date_gmt, post_name,
 *                                  menu_order, comment_status, plus sticky (bool), meta {key: value},
 *                                  terms {taxonomy: [ids]}, format (slug), thumbnail (attachment id), template
 *                                  (slug). Type "attachment" is inserted with wp_insert_attachment as a 1x1 PNG
 *                                  image written into the uploads directory (harness rule)
 *   update_post {site, as, id, fields}  wp_update_post with the same "fields" (harness rule: fields as above)
 *   revision {site, as, id, title, content}  wp_update_post of title and content as the user, which makes WordPress's
 *                                  revision on update; answers {id} of the newest revision the update made
 *   lock {site, post, user, age}   _edit_lock of the post set to "<now - age>:<user id>" (update_post_meta); answers
 *                                  {value}
 *   backdate {site, posts: [{id, at, date}]}  sets each post's post_modified and post_modified_gmt to "at" (UTC,
 *                                  yyyy-MM-ddTHH:mm:ss) and, when "date" is true, its post_date too (and
 *                                  post_date_gmt unless the post's date floats), with direct column updates (harness
 *                                  rule); answers {changed}
 *   own_site {site, user, path, title}  creates a site on localhost at path with the user as its administrator and
 *                                  removes the user from the site at path "site"; answers {id}
 *   latest_post {site, type, status}  the highest id of that type and status on the site, or 0 (harness rule)
 *   cookie_session {user}          a login session of the user: answers {cookies: [{name, value}] (the auth and
 *                                  logged_in cookies), rest_nonce (action wp_rest), session (the session token)}
 *   logout {user, session}         destroys that session token (WP_Session_Tokens::destroy); answers {destroyed}
 *   nonce {user, session, action}  wp_create_nonce for the user in that session; answers {nonce}
 *   meta {site, post, key}         get_post_meta, every row; answers {values: [...]}
 *   set_meta {site, post, key, value}  update_post_meta; answers {updated}
 *   autosave {site, as, post, title, content}  wp_create_post_autosave as the user; answers {id} (or the error code)
 *   auto_draft {site, as, type}    the auto-draft WordPress's new-post screen creates for the user: the new-post
 *                                  default of the type, inserted (get_default_post_to_edit with create_in_db, as
 *                                  post-new.php calls it; harness rule: no capability check, no lock); answers {id}
 *   taxonomy_args {name}           the public properties of the registered taxonomy object (get_taxonomy)
 *   type_args {name}               the public properties of the registered post type object (get_post_type_object)
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
    // Harness rule: the scenario site has the main site's /%postname%/ permalinks (a network's subdirectory site
    // defaults to a dated structure) and holds the custom type book, as QuarkPress's test application does.
    global $wp_rewrite;
    update_option('permalink_structure', '/%postname%/');
    $wp_rewrite->init();
    flush_rewrite_rules();
    update_option('qp_parity_book_type', '1');
    $refs = ['t:uncategorized' => (int) get_option('default_category')];
    foreach (['alice', 'bob', 'carol', 'dave', 'erin'] as $login) {
        $refs['u:' . $login] = qp_user_id(['user' => $login]);
    }
    restore_current_blog();
    return ['path' => (string) $input['path'], 'refs' => $refs];
}

/** The wp_insert_post / wp_update_post arguments among "fields" (harness rule: the keys the brief names). */
const QP_POST_FIELDS = ['post_content', 'post_excerpt', 'post_password', 'post_parent', 'post_date', 'post_date_gmt',
    'post_name', 'menu_order', 'comment_status'];

/** A 1x1 opaque PNG (harness rule: the image an attachment fixture holds). */
const QP_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8DwHwAFAAH/'
    . 'q842iQAAAABJRU5ErkJggg==';

function qp_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $author = qp_user_id($input, 'as');
    wp_set_current_user($author);
    $fields = isset($input['fields']) && is_array($input['fields']) ? $input['fields'] : [];
    $args = [
        'post_type' => (string) $input['type'],
        'post_status' => (string) $input['status'],
        'post_title' => (string) $input['title'],
        'post_author' => $author,
    ];
    foreach (QP_POST_FIELDS as $field) {
        if (array_key_exists($field, $fields)) {
            $args[$field] = $fields[$field];
        }
    }
    if ('attachment' === $args['post_type']) {
        // Harness rule: an image attachment is a 1x1 PNG written into the uploads directory, as a media upload would.
        $upload = wp_upload_bits('qp-' . sanitize_title((string) $input['title']) . '.png', null,
            base64_decode(QP_PNG_BASE64));
        if (!empty($upload['error'])) {
            throw new RuntimeException('wp_upload_bits: ' . $upload['error']);
        }
        $args['post_mime_type'] = 'image/png';
        $args['guid'] = $upload['url'];
        $id = wp_insert_attachment($args, $upload['file'], (int) ($args['post_parent'] ?? 0), true);
    } else {
        $id = wp_insert_post($args, true);
    }
    if (is_wp_error($id)) {
        wp_set_current_user(0);
        restore_current_blog();
        throw new RuntimeException('wp_insert_post: ' . $id->get_error_code());
    }
    qp_apply_extras((int) $id, $fields);
    wp_set_current_user(0);
    restore_current_blog();
    return ['id' => (int) $id];
}

/** The non-column "fields": sticky, meta, terms, format, thumbnail, template (harness rule: applied after the row). */
function qp_apply_extras(int $id, array $fields): void
{
    if (array_key_exists('sticky', $fields)) {
        $fields['sticky'] ? stick_post($id) : unstick_post($id);
    }
    if (isset($fields['meta']) && is_array($fields['meta'])) {
        foreach ($fields['meta'] as $key => $value) {
            update_post_meta($id, (string) $key, $value);
        }
    }
    if (isset($fields['terms']) && is_array($fields['terms'])) {
        foreach ($fields['terms'] as $taxonomy => $ids) {
            $set = wp_set_post_terms($id, array_map('intval', (array) $ids), (string) $taxonomy);
            if (is_wp_error($set)) {
                throw new RuntimeException("wp_set_post_terms($taxonomy): " . $set->get_error_code());
            }
        }
    }
    if (isset($fields['format'])) {
        $set = set_post_format($id, (string) $fields['format']);
        if (is_wp_error($set)) {
            throw new RuntimeException('set_post_format: ' . $set->get_error_code());
        }
    }
    if (isset($fields['thumbnail'])) {
        if (!set_post_thumbnail($id, (int) $fields['thumbnail'])) {
            throw new RuntimeException('set_post_thumbnail refused ' . $fields['thumbnail']);
        }
    }
    if (array_key_exists('template', $fields)) {
        update_post_meta($id, '_wp_page_template', (string) $fields['template']);
    }
}

function qp_update_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    wp_set_current_user(qp_user_id($input, 'as'));
    $fields = isset($input['fields']) && is_array($input['fields']) ? $input['fields'] : [];
    $args = ['ID' => (int) $input['id']];
    foreach (QP_POST_FIELDS as $field) {
        if (array_key_exists($field, $fields)) {
            $args[$field] = $fields[$field];
        }
    }
    foreach (['post_title', 'post_status'] as $field) {
        if (array_key_exists($field, $fields)) {
            $args[$field] = $fields[$field];
        }
    }
    $id = wp_update_post($args, true);
    if (is_wp_error($id)) {
        wp_set_current_user(0);
        restore_current_blog();
        throw new RuntimeException('wp_update_post: ' . $id->get_error_code());
    }
    qp_apply_extras((int) $id, $fields);
    wp_set_current_user(0);
    restore_current_blog();
    return ['id' => (int) $id];
}

/** Harness rule: an update of title and content as the user; the revision WordPress makes on update is the answer. */
function qp_revision(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    wp_set_current_user(qp_user_id($input, 'as'));
    if (function_exists('qp_parity_register_book')) {
        // the scenario site's custom type, registered by the mu-plugin for the served site only (harness rule)
        qp_parity_register_book();
    }
    $post = (int) $input['id'];
    $before = array_keys(wp_get_post_revisions($post));
    $updated = wp_update_post([
        'ID' => $post,
        'post_title' => (string) $input['title'],
        'post_content' => (string) $input['content'],
    ], true);
    if (is_wp_error($updated)) {
        wp_set_current_user(0);
        restore_current_blog();
        throw new RuntimeException('wp_update_post: ' . $updated->get_error_code());
    }
    $new = array_values(array_diff(array_keys(wp_get_post_revisions($post)), $before));
    wp_set_current_user(0);
    restore_current_blog();
    if (empty($new)) {
        throw new RuntimeException("the update of post $post made no revision");
    }
    return ['id' => (int) max($new), 'made' => count($new)];
}

/** Harness rule: the edit lock as the editor writes it, aged by "age" seconds. */
function qp_lock(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $value = (time() - (int) $input['age']) . ':' . qp_user_id($input);
    update_post_meta((int) $input['post'], '_edit_lock', $value);
    restore_current_blog();
    return ['value' => $value];
}

/**
 * Harness rule: the dates the scenario's setup leaves on its posts, written to the columns directly, so that only a
 * request's own writes make a date "now". A floating date (post_date_gmt zero) keeps floating.
 */
function qp_backdate(array $input): array
{
    global $wpdb;
    switch_to_blog(qp_site_id((string) $input['site']));
    $changed = 0;
    foreach ($input['posts'] as $post) {
        $id = (int) $post['id'];
        $gmt = str_replace('T', ' ', (string) $post['at']);
        $local = get_date_from_gmt($gmt);
        $columns = ['post_modified' => $local, 'post_modified_gmt' => $gmt];
        if (!empty($post['date'])) {
            $columns['post_date'] = $local;
            $stored = $wpdb->get_var($wpdb->prepare("SELECT post_date_gmt FROM $wpdb->posts WHERE ID = %d", $id));
            if (null !== $stored && '0000-00-00 00:00:00' !== $stored) {
                $columns['post_date_gmt'] = $gmt;
            }
        }
        $updated = $wpdb->update($wpdb->posts, $columns, ['ID' => $id]);
        if (false === $updated) {
            restore_current_blog();
            throw new RuntimeException("backdate of post $id failed: " . $wpdb->last_error);
        }
        $changed += (int) $updated;
        clean_post_cache($id);
    }
    restore_current_blog();
    return ['changed' => $changed];
}

/** Harness rule: the user becomes administrator of a new site and leaves the scenario's site. */
function qp_own_site(array $input): array
{
    $user = qp_user_id($input);
    $id = wpmu_create_blog('localhost', (string) $input['path'], (string) $input['title'], $user);
    if (is_wp_error($id)) {
        throw new RuntimeException('wpmu_create_blog: ' . $id->get_error_code());
    }
    $removed = remove_user_from_blog($user, qp_site_id((string) $input['site']));
    if (is_wp_error($removed)) {
        throw new RuntimeException('remove_user_from_blog: ' . $removed->get_error_code());
    }
    return ['id' => (int) $id];
}

function qp_latest_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $found = get_posts([
        'post_type' => (string) $input['type'],
        'post_status' => (string) $input['status'],
        'numberposts' => 1,
        'orderby' => 'ID',
        'order' => 'DESC',
        'suppress_filters' => true,
    ]);
    restore_current_blog();
    return ['id' => empty($found) ? 0 : (int) $found[0]->ID];
}

/** Harness rule: a login session as wp-login.php would create it, with its two cookies and the REST nonce. */
function qp_cookie_session(array $input): array
{
    $user = qp_user_id($input);
    $expiration = time() + 2 * DAY_IN_SECONDS;
    $manager = WP_Session_Tokens::get_instance($user);
    $token = $manager->create($expiration);
    $auth = wp_generate_auth_cookie($user, $expiration, 'auth', $token);
    $logged_in = wp_generate_auth_cookie($user, $expiration, 'logged_in', $token);
    $_COOKIE[LOGGED_IN_COOKIE] = $logged_in;
    wp_set_current_user($user);
    $nonce = wp_create_nonce('wp_rest');
    wp_set_current_user(0);
    return [
        'cookies' => [
            ['name' => AUTH_COOKIE, 'value' => $auth],
            ['name' => LOGGED_IN_COOKIE, 'value' => $logged_in],
        ],
        'rest_nonce' => $nonce,
        'session' => $token,
    ];
}

function qp_logout(array $input): array
{
    $user = qp_user_id($input);
    $manager = WP_Session_Tokens::get_instance($user);
    $existed = null !== $manager->get((string) $input['session']);
    $manager->destroy((string) $input['session']);
    return ['destroyed' => $existed];
}

function qp_nonce(array $input): array
{
    $user = qp_user_id($input);
    // Harness rule: the nonce is bound to the session token, which WordPress reads from the logged_in cookie.
    $_COOKIE[LOGGED_IN_COOKIE] = wp_generate_auth_cookie($user, time() + DAY_IN_SECONDS, 'logged_in',
        (string) $input['session']);
    wp_set_current_user($user);
    $nonce = wp_create_nonce((string) $input['action']);
    wp_set_current_user(0);
    return ['nonce' => $nonce];
}

function qp_meta(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $values = get_post_meta((int) $input['post'], (string) $input['key'], false);
    restore_current_blog();
    return ['values' => array_values($values)];
}

function qp_set_meta(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $updated = update_post_meta((int) $input['post'], (string) $input['key'], $input['value']);
    restore_current_blog();
    return ['updated' => false !== $updated];
}

function qp_autosave(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    if (function_exists('qp_parity_register_book')) {
        // the scenario site's custom type, registered by the mu-plugin for the served site only (harness rule)
        qp_parity_register_book();
    }
    wp_set_current_user(qp_user_id($input, 'as'));
    $post = get_post((int) $input['post']);
    if (null === $post) {
        wp_set_current_user(0);
        restore_current_blog();
        throw new InvalidArgumentException('no such post: ' . $input['post']);
    }
    require_once ABSPATH . 'wp-admin/includes/post.php';
    $result = wp_create_post_autosave([
        'post_ID' => $post->ID,
        'post_type' => $post->post_type,
        'post_title' => (string) $input['title'],
        'post_content' => (string) $input['content'],
        'post_excerpt' => $post->post_excerpt,
    ]);
    wp_set_current_user(0);
    restore_current_blog();
    if (is_wp_error($result)) {
        return ['id' => 0, 'error' => $result->get_error_code()];
    }
    return ['id' => (int) $result];
}

/**
 * Harness rule: the row post-new.php creates, without the screen's capability check and without the lock it takes
 * (the auto-draft's capability rule and the lock are compared through the routes themselves).
 */
function qp_auto_draft(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    wp_set_current_user(qp_user_id($input, 'as'));
    if (function_exists('qp_parity_register_book')) {
        // the scenario site's custom type, registered by the mu-plugin for the served site only (harness rule)
        qp_parity_register_book();
    }
    require_once ABSPATH . 'wp-admin/includes/post.php';
    $post = get_default_post_to_edit((string) $input['type'], true);
    wp_set_current_user(0);
    restore_current_blog();
    if (!($post instanceof WP_Post) || 'auto-draft' !== $post->post_status) {
        return ['id' => 0, 'error' => 'no auto-draft was inserted'];
    }
    return ['id' => (int) $post->ID];
}

function qp_taxonomy_args(array $input): array
{
    $taxonomy = get_taxonomy((string) $input['name']);
    if (false === $taxonomy) {
        throw new InvalidArgumentException('no such taxonomy: ' . $input['name']);
    }
    return ['args' => get_object_vars($taxonomy)];
}

function qp_type_args(array $input): array
{
    $type = get_post_type_object((string) $input['name']);
    if (null === $type) {
        throw new InvalidArgumentException('no such post type: ' . $input['name']);
    }
    return ['args' => get_object_vars($type)];
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
        'update_post' => qp_update_post($qp_input),
        'revision' => qp_revision($qp_input),
        'lock' => qp_lock($qp_input),
        'backdate' => qp_backdate($qp_input),
        'own_site' => qp_own_site($qp_input),
        'latest_post' => qp_latest_post($qp_input),
        'cookie_session' => qp_cookie_session($qp_input),
        'logout' => qp_logout($qp_input),
        'nonce' => qp_nonce($qp_input),
        'meta' => qp_meta($qp_input),
        'set_meta' => qp_set_meta($qp_input),
        'autosave' => qp_autosave($qp_input),
        'auto_draft' => qp_auto_draft($qp_input),
        'taxonomy_args' => qp_taxonomy_args($qp_input),
        'type_args' => qp_type_args($qp_input),
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
