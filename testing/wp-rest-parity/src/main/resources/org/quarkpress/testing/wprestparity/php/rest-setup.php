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
 *   delete_meta {site, post, key}  delete_post_meta of every value; answers {deleted}
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
 * Media facts operations (each a harness rule; paths are relative to the site's uploads base):
 *   extensions {}                  {gd, gd_webp, gd_avif (gd_info's WebP/AVIF support), imagick, dom, exif, fileinfo,
 *                                  php}
 *   probe {site?, as?, locale?, locale_filter?, no_convert?, add_image_size?, fn, args | calls}  black-box calls of
 *                                  sanitize_file_name, wp_unique_filename, wp_check_filetype(_and_ext),
 *                                  wp_get_image_mime, wp_get_registered_image_subsizes, image_resize_dimensions,
 *                                  wp_constrain_dimensions, wp_read_image_metadata, wp_exif_frac2dec, wp_exif_date2ts,
 *                                  round, get_allowed_mime_types, get_locale (see qp_probe)
 *   prepare_folder {site, folder | current, files: {name: base64}, clear?}  answers {folder, listing}
 *   uploads_listing {site, folder} the folder's files, recursively: [{path, size, md5}]
 *   clear_uploads {site}           empties a non-main site's uploads base
 *   attachment {site, id}          the post row, every meta value raw, the metadata, the folder's listing
 *   network_option {name} / set_network_option {name, value}  get_site_option / update_site_option (null deletes)
 *   get_option {site, name}        get_option on the site
 *   query_attachments {site, args: {post_mime_type}}  get_posts of inherit attachments, ids ascending
 *   remove_upload {site, file}     deletes one file of the site's uploads
 * REST media facts operations (MediaRestFactsDbTest; each a harness rule):
 *   role {site, name, caps}        adds the role "name" on the site with exactly the capabilities "caps" (a list),
 *                                  gives it to the network user "uploader" (created on first use, a member of no other
 *                                  site) on that site and creates an application password for him; answers
 *                                  {user, id, password, role, caps}
 *   trash_post {site, id}          wp_trash_post; answers {trashed}
 *   delete_post {site, id, force}  wp_delete_post; answers {deleted}
 *   batch {as, requests}           the body of a POST /batch/v1 holding the given sub-requests ({method, path, body?}
 *                                  each, in order); answers {as, body}. It only builds the body: the test sends it
 *                                  through the REST client as "as", so the exchange is recorded
 * Editor-extras facts operations (EditorExtrasFactsDbTest; each a harness rule):
 *   site_admin {site}              the network user "sadmin" (created on first use), administrator of the site at
 *                                  path "site" and not a super admin, with a new application password; answers
 *                                  {user, id, password}
 *   options_like {site, prefix}    the names of the site's options starting with the prefix, sorted; answers {names}
 *   fileupload_maxk {value}        sets the network option fileupload_maxk (null deletes it); answers {previous,
 *                                  value, upload_space_check_disabled}
 *   write_upload {site, name, bytes}  writes the file "name" (bytes in base64) into the uploads base of the site at
 *                                  path "site"; answers {file, url}
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
    if (function_exists('qp_parity_register_hidden')) {
        // the item site's non-REST type, registered by the mu-plugin for the served site only (harness rule)
        qp_parity_register_hidden();
    }
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

/** Harness rule: delete_post_meta of every value of the key (the media facts' "metadata row deleted" case). */
function qp_delete_meta(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $deleted = delete_post_meta((int) $input['post'], (string) $input['key']);
    restore_current_blog();
    return ['deleted' => $deleted];
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

// ---- media facts operations (MediaFactsDbTest) ---------------------------------------------------------------------

/** Harness rule: which PHP extensions the server has (the facts run skips the editor items without GD). */
function qp_extensions(): array
{
    $gd = function_exists('gd_info') ? gd_info() : [];
    return [
        'gd' => extension_loaded('gd'),
        'gd_webp' => !empty($gd['WebP Support']),
        'gd_avif' => !empty($gd['AVIF Support']),
        'imagick' => extension_loaded('imagick'),
        'dom' => class_exists('DOMDocument'),
        'exif' => extension_loaded('exif'),
        'fileinfo' => extension_loaded('fileinfo'),
        'php' => PHP_VERSION,
    ];
}

/** The site's uploads base folder (wp_upload_dir's basedir, created on demand); the caller has switched to it. */
function qp_uploads_base(): string
{
    $dir = wp_upload_dir(null, true);
    if (!empty($dir['error'])) {
        throw new RuntimeException('wp_upload_dir: ' . $dir['error']);
    }
    return rtrim($dir['basedir'], '/');
}

/** Harness rule: a relative path inside the uploads base, refused when it climbs out of it. */
function qp_inside(string $base, string $relative): string
{
    if (str_contains('/' . $relative . '/', '/../') || str_starts_with($relative, '/')) {
        throw new InvalidArgumentException("not a path inside the uploads base: $relative");
    }
    return '' === $relative ? $base : $base . '/' . $relative;
}

/** Every file under $dir, recursively: {path (relative to $root), size, md5}, sorted by path (harness rule). */
function qp_listing(string $dir, string $root, bool $recursive = true): array
{
    $out = [];
    if (!is_dir($dir)) {
        return $out;
    }
    $entries = scandir($dir);
    sort($entries, SORT_STRING);
    foreach ($entries as $entry) {
        if ('.' === $entry || '..' === $entry) {
            continue;
        }
        $path = $dir . '/' . $entry;
        $relative = ltrim(substr($path, strlen($root)), '/');
        if (is_dir($path)) {
            $out[] = ['path' => $relative . '/', 'size' => null, 'md5' => null];
            if ($recursive) {
                array_push($out, ...qp_listing($path, $root, true));
            }
        } else {
            $out[] = ['path' => $relative, 'size' => filesize($path), 'md5' => md5_file($path)];
        }
    }
    return $out;
}

function qp_remove_tree(string $dir): void
{
    if (!is_dir($dir)) {
        return;
    }
    foreach (scandir($dir) as $entry) {
        if ('.' === $entry || '..' === $entry) {
            continue;
        }
        $path = $dir . '/' . $entry;
        is_dir($path) && !is_link($path) ? qp_remove_tree($path) : unlink($path);
    }
    rmdir($dir);
}

/**
 * Harness rule: empties the uploads base of a (non-main) site, so a site id that an earlier run of the shared tree
 * used starts without files, and an item leaves none behind.
 */
function qp_clear_uploads(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $base = qp_uploads_base();
    restore_current_blog();
    if (!str_contains($base, '/sites/')) {
        throw new InvalidArgumentException("refusing to clear the main site's uploads: $base");
    }
    qp_remove_tree($base);
    return ['cleared' => $base];
}

/** A file written into the run directory from {base64, name} (harness rule: the probes' input files). */
function qp_probe_file(array $spec): string
{
    global $qp_output_file;
    $dir = dirname($qp_output_file) . '/probe-files';
    if (!is_dir($dir) && !mkdir($dir, 0777, true) && !is_dir($dir)) {
        throw new RuntimeException("cannot create $dir");
    }
    $name = (string) ($spec['name'] ?? 'upload.tmp');
    if (str_contains($name, '/')) {
        throw new InvalidArgumentException("a probe file name has no folder: $name");
    }
    $path = $dir . '/' . $name;
    file_put_contents($path, base64_decode((string) $spec['base64'], true));
    return $path;
}

/** JSON-safe copy of a probe answer: a string that is not valid UTF-8 becomes {"invalid_utf8_hex": ...}. */
function qp_json_safe(mixed $value): mixed
{
    if (is_string($value)) {
        return mb_check_encoding($value, 'UTF-8') ? $value : ['invalid_utf8_hex' => bin2hex($value)];
    }
    if (is_array($value)) {
        $out = [];
        foreach ($value as $key => $item) {
            $out[$key] = qp_json_safe($item);
        }
        return $out;
    }
    if (is_object($value)) {
        return qp_json_safe(get_object_vars($value));
    }
    if (is_float($value) && (is_nan($value) || is_infinite($value))) {
        return ['float' => (string) $value];
    }
    return $value;
}

/** The functions the probe operation may call (black-box calls of WordPress's public functions, plus PHP's round). */
const QP_PROBE_FUNCTIONS = ['sanitize_file_name', 'wp_unique_filename', 'wp_check_filetype', 'wp_check_filetype_and_ext',
    'wp_get_image_mime', 'wp_get_registered_image_subsizes', 'image_resize_dimensions', 'wp_constrain_dimensions',
    'wp_read_image_metadata', 'wp_exif_frac2dec', 'wp_exif_date2ts', 'round', 'get_allowed_mime_types', 'get_locale'];

/** One probe call: {fn, args}; file arguments are {base64, name} objects (harness rule: written to the run dir). */
function qp_probe_one(array $call): mixed
{
    $fn = (string) $call['fn'];
    $args = isset($call['args']) && is_array($call['args']) ? $call['args'] : [];
    if (!in_array($fn, QP_PROBE_FUNCTIONS, true)) {
        throw new InvalidArgumentException("not a probe function: $fn");
    }
    switch ($fn) {
        case 'wp_unique_filename':
            // Harness rule: args [folder relative to the site's uploads base, name].
            return wp_unique_filename(qp_inside(qp_uploads_base(), (string) $args[0]), (string) $args[1]);
        case 'wp_check_filetype_and_ext':
            // Harness rule: args [{base64, name: the temporary file's name}, client name].
            return wp_check_filetype_and_ext(qp_probe_file($args[0]), (string) $args[1]);
        case 'wp_get_image_mime':
        case 'wp_read_image_metadata':
            return $fn(qp_probe_file($args[0]));
        case 'get_allowed_mime_types':
            // Harness rule: args [login].
            return get_allowed_mime_types(qp_user_id(['user' => (string) $args[0]]));
        case 'round':
            return round((float) $args[0], (int) ($args[1] ?? 0));
        default:
            return $fn(...$args);
    }
}

/**
 * Harness rule: calls WordPress's public functions as a black box on the site at path "site" (default the main
 * site), as user "as" (default nobody), with these optional settings: "locale" (switch_locale, its answer recorded),
 * "locale_filter" (the locale filter answers that value), "no_convert" (image_editor_output_format answers an empty
 * map, what the REST upload's convert_format=false does), "add_image_size" ([name, width, height, crop]). Input
 * {fn, args} answers {result, result_type}; input {calls: [{fn, args}...]} answers {results: [...], result_types:
 * [...]} (gettype() of each answer).
 */
function qp_probe(array $input): array
{
    require_once ABSPATH . 'wp-admin/includes/file.php';
    require_once ABSPATH . 'wp-admin/includes/image.php';
    require_once ABSPATH . 'wp-admin/includes/media.php';
    switch_to_blog(isset($input['site']) ? qp_site_id((string) $input['site']) : get_main_site_id());
    if (isset($input['as'])) {
        wp_set_current_user(qp_user_id($input, 'as'));
    }
    $out = [];
    if (isset($input['locale'])) {
        // switch_locale is not loaded in every context; its absence is recorded, not worked around.
        $out['switch_locale'] = function_exists('switch_locale') ? switch_locale((string) $input['locale'])
            : 'switch_locale is not defined here';
    }
    if (isset($input['locale_filter'])) {
        $locale = (string) $input['locale_filter'];
        add_filter('locale', static fn() => $locale, 1000);
    }
    if (!empty($input['no_convert'])) {
        add_filter('image_editor_output_format', static fn() => [], 1000);
    }
    if (isset($input['add_image_size'])) {
        [$name, $width, $height, $crop] = $input['add_image_size'];
        add_image_size((string) $name, (int) $width, (int) $height, $crop);
    }
    $out['locale'] = get_locale();
    try {
        if (isset($input['calls'])) {
            $results = [];
            $types = [];
            foreach ($input['calls'] as $call) {
                $value = qp_probe_one($call);
                $results[] = qp_json_safe($value);
                $types[] = gettype($value);
            }
            $out['results'] = $results;
            // Harness rule: gettype() of each answer, since JSON writes the float 5.0 as 5.
            $out['result_types'] = $types;
        } else {
            $value = qp_probe_one($input);
            $out['result'] = qp_json_safe($value);
            $out['result_type'] = gettype($value);
        }
    } finally {
        if (isset($input['locale']) && function_exists('restore_previous_locale')) {
            restore_previous_locale();
        }
        wp_set_current_user(0);
        restore_current_blog();
    }
    return $out;
}

/**
 * Harness rule: fills a folder of the site's uploads (relative "folder", or the current upload folder with
 * "current": true) with files {name: base64}; "clear" (default true) empties it first. Answers the folder relative to
 * the uploads base and its listing.
 */
function qp_prepare_folder(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $base = qp_uploads_base();
    $dir = !empty($input['current']) ? rtrim(wp_upload_dir()['path'], '/') : qp_inside($base, (string) $input['folder']);
    restore_current_blog();
    if (!array_key_exists('clear', $input) || $input['clear']) {
        qp_remove_tree($dir);
    }
    if (!is_dir($dir) && !mkdir($dir, 0777, true) && !is_dir($dir)) {
        throw new RuntimeException("cannot create $dir");
    }
    foreach (($input['files'] ?? []) as $name => $base64) {
        if (str_contains((string) $name, '/')) {
            throw new InvalidArgumentException("a prepared file name has no folder: $name");
        }
        file_put_contents($dir . '/' . $name, base64_decode((string) $base64, true));
    }
    return ['folder' => ltrim(substr($dir, strlen($base)), '/'), 'listing' => qp_listing($dir, $base, false)];
}

/** Harness rule: the sorted listing (path, size, md5) of a folder of the site's uploads, recursively. */
function qp_uploads_listing(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $base = qp_uploads_base();
    restore_current_blog();
    return ['listing' => qp_listing(qp_inside($base, (string) ($input['folder'] ?? '')), $base)];
}

/**
 * The attachment as stored: the post row's columns, every meta value as stored (raw), the attachment metadata
 * (wp_get_attachment_metadata, unfiltered) and the listing of the attached file's folder. {exists: false} when there
 * is no such post.
 */
function qp_attachment(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $id = (int) $input['id'];
    $post = get_post($id);
    if (null === $post) {
        restore_current_blog();
        return ['exists' => false, 'id' => $id];
    }
    $row = [];
    foreach (['ID', 'post_type', 'post_title', 'post_excerpt', 'post_content', 'post_status', 'post_mime_type',
                 'post_name', 'post_parent', 'post_author', 'guid', 'comment_status', 'ping_status', 'menu_order',
                 'post_date', 'post_date_gmt', 'post_modified', 'post_modified_gmt'] as $column) {
        $row[$column] = $post->$column;
    }
    global $wpdb;
    $meta = [];
    $rows = $wpdb->get_results($wpdb->prepare(
        "SELECT meta_key, meta_value FROM $wpdb->postmeta WHERE post_id = %d ORDER BY meta_id", $id), ARRAY_A);
    foreach ($rows as $meta_row) {
        $meta[$meta_row['meta_key']][] = $meta_row['meta_value'];
    }
    $base = qp_uploads_base();
    $file = get_attached_file($id, true);
    $listing = is_string($file) && '' !== $file ? qp_listing(dirname($file), $base, false) : [];
    $answer = [
        'exists' => true,
        'site_id' => get_current_blog_id(),
        'row' => qp_json_safe($row),
        'meta' => qp_json_safe($meta),
        'metadata' => qp_json_safe(wp_get_attachment_metadata($id, true)),
        'attached_file' => $file,
        'uploads' => ['basedir' => substr($base, strlen(rtrim(ABSPATH, '/'))), 'baseurl' => wp_upload_dir()['baseurl']],
        'folder_listing' => $listing,
    ];
    restore_current_blog();
    return $answer;
}

function qp_network_option(array $input): array
{
    return ['name' => $input['name'], 'value' => get_site_option((string) $input['name'], null)];
}

/** update_site_option; a null value deletes the network option (harness rule: restoring an absent option). */
function qp_set_network_option(array $input): array
{
    $name = (string) $input['name'];
    if (null === $input['value']) {
        delete_site_option($name);
    } else {
        update_site_option($name, $input['value']);
    }
    return ['name' => $name, 'value' => get_site_option($name, null)];
}

/** Harness rule: reads an option of the site at path "site" (null when absent). */
function qp_get_option(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $value = get_option((string) $input['name'], null);
    restore_current_blog();
    return ['name' => $input['name'], 'value' => $value];
}

/** get_posts of attachments in status inherit with the given post_mime_type, ids ascending (harness rule). */
function qp_query_attachments(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $args = $input['args'] ?? [];
    $found = get_posts([
        'post_type' => 'attachment',
        'post_status' => 'inherit',
        'post_mime_type' => $args['post_mime_type'] ?? '',
        'orderby' => 'ID',
        'order' => 'ASC',
        'numberposts' => -1,
    ]);
    $out = [];
    foreach ($found as $post) {
        $out[] = ['id' => (int) $post->ID, 'post_mime_type' => $post->post_mime_type];
    }
    restore_current_blog();
    return ['posts' => $out];
}

/** Harness rule: deletes one file of the site's uploads (a path relative to its uploads base). */
function qp_remove_upload(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $path = qp_inside(qp_uploads_base(), (string) $input['file']);
    restore_current_blog();
    return ['removed' => is_file($path) && unlink($path)];
}

// ---- REST media facts operations (MediaRestFactsDbTest) -----------------------------------------------------------

/**
 * Harness rule: a role holding exactly the given capabilities on the site at path "site", given to the network user
 * "uploader" there (created on first use with wpmu_create_user, so he has no role on any other site), with a new
 * application password.
 */
function qp_role(array $input): array
{
    $site = qp_site_id((string) $input['site']);
    $name = (string) $input['name'];
    $caps = [];
    foreach ((array) $input['caps'] as $cap) {
        $caps[(string) $cap] = true;
    }
    switch_to_blog($site);
    remove_role($name);
    if (null === add_role($name, $name, $caps)) {
        restore_current_blog();
        throw new RuntimeException("add_role($name) refused");
    }
    restore_current_blog();
    $user = get_user_by('login', 'uploader');
    $id = false === $user ? wpmu_create_user('uploader', 'uploader', 'uploader@quarkpress.test') : $user->ID;
    if (false === $id) {
        throw new RuntimeException('wpmu_create_user refused uploader');
    }
    $added = add_user_to_blog($site, (int) $id, $name);
    if (is_wp_error($added)) {
        throw new RuntimeException('add_user_to_blog(uploader): ' . $added->get_error_code());
    }
    $created = WP_Application_Passwords::create_new_application_password((int) $id, ['name' => 'parity']);
    if (is_wp_error($created)) {
        throw new RuntimeException('create_new_application_password(uploader): ' . $created->get_error_code());
    }
    return ['user' => 'uploader', 'id' => (int) $id, 'password' => $created[0], 'role' => $name,
        'caps' => array_keys($caps)];
}

function qp_trash_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $trashed = wp_trash_post((int) $input['id']);
    restore_current_blog();
    return ['trashed' => $trashed instanceof WP_Post];
}

function qp_delete_post(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $deleted = wp_delete_post((int) $input['id'], !empty($input['force']));
    restore_current_blog();
    return ['deleted' => $deleted instanceof WP_Post];
}

/** Harness rule: the batch route's body {"requests": [{method, path, body?}...]}, nothing else. */
function qp_batch(array $input): array
{
    $requests = [];
    foreach ((array) $input['requests'] as $request) {
        $sub = ['method' => (string) $request['method'], 'path' => (string) $request['path']];
        if (array_key_exists('body', $request)) {
            $sub['body'] = $request['body'];
        }
        $requests[] = $sub;
    }
    return ['as' => (string) $input['as'], 'body' => ['requests' => $requests]];
}

// ---- Editor-extras facts operations (EditorExtrasFactsDbTest) -----------------------------------------------------

/**
 * Harness rule: the network user "sadmin" (created on first use with wpmu_create_user, never a super admin) made
 * administrator of the site at path "site", with a new application password.
 */
function qp_site_admin(array $input): array
{
    $site = qp_site_id((string) $input['site']);
    $user = get_user_by('login', 'sadmin');
    $id = false === $user ? wpmu_create_user('sadmin', 'sadmin', 'sadmin@quarkpress.test') : $user->ID;
    if (false === $id) {
        throw new RuntimeException('wpmu_create_user refused sadmin');
    }
    if (is_super_admin((int) $id)) {
        throw new RuntimeException('sadmin must not be a super admin');
    }
    $added = add_user_to_blog($site, (int) $id, 'administrator');
    if (is_wp_error($added)) {
        throw new RuntimeException('add_user_to_blog(sadmin): ' . $added->get_error_code());
    }
    $created = WP_Application_Passwords::create_new_application_password((int) $id, ['name' => 'parity']);
    if (is_wp_error($created)) {
        throw new RuntimeException('create_new_application_password(sadmin): ' . $created->get_error_code());
    }
    return ['user' => 'sadmin', 'id' => (int) $id, 'password' => $created[0]];
}

/** Harness rule: the names of the site's options that start with the prefix, read from its options table. */
function qp_options_like(array $input): array
{
    global $wpdb;
    switch_to_blog(qp_site_id((string) $input['site']));
    $names = $wpdb->get_col($wpdb->prepare(
        "SELECT option_name FROM $wpdb->options WHERE option_name LIKE %s ORDER BY option_name",
        $wpdb->esc_like((string) $input['prefix']) . '%'));
    restore_current_blog();
    return ['names' => array_values($names)];
}

/** Harness rule: sets (or, for null, deletes) the network option fileupload_maxk. */
function qp_fileupload_maxk(array $input): array
{
    $previous = get_site_option('fileupload_maxk', null);
    if (null === $input['value']) {
        delete_site_option('fileupload_maxk');
    } else {
        update_site_option('fileupload_maxk', $input['value']);
    }
    return ['previous' => $previous, 'value' => get_site_option('fileupload_maxk', null),
        'upload_space_check_disabled' => get_site_option('upload_space_check_disabled', null)];
}

/** Harness rule: a file written into the uploads base of the site at path "site", answered with its URL. */
function qp_write_upload(array $input): array
{
    switch_to_blog(qp_site_id((string) $input['site']));
    $name = (string) $input['name'];
    if ('' === $name || str_contains($name, '/')) {
        restore_current_blog();
        throw new InvalidArgumentException("not a file name: $name");
    }
    $path = qp_inside(qp_uploads_base(), $name);
    $bytes = base64_decode((string) $input['bytes'], true);
    if (false === $bytes || false === file_put_contents($path, $bytes)) {
        restore_current_blog();
        throw new RuntimeException("cannot write $path");
    }
    $url = wp_upload_dir(null, false)['baseurl'] . '/' . rawurlencode($name);
    restore_current_blog();
    return ['file' => $name, 'url' => $url];
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
        'delete_meta' => qp_delete_meta($qp_input),
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
        'extensions' => qp_extensions(),
        'probe' => qp_probe($qp_input),
        'prepare_folder' => qp_prepare_folder($qp_input),
        'uploads_listing' => qp_uploads_listing($qp_input),
        'clear_uploads' => qp_clear_uploads($qp_input),
        'attachment' => qp_attachment($qp_input),
        'network_option' => qp_network_option($qp_input),
        'set_network_option' => qp_set_network_option($qp_input),
        'get_option' => qp_get_option($qp_input),
        'query_attachments' => qp_query_attachments($qp_input),
        'remove_upload' => qp_remove_upload($qp_input),
        'role' => qp_role($qp_input),
        'trash_post' => qp_trash_post($qp_input),
        'delete_post' => qp_delete_post($qp_input),
        'batch' => qp_batch($qp_input),
        'site_admin' => qp_site_admin($qp_input),
        'options_like' => qp_options_like($qp_input),
        'fileupload_maxk' => qp_fileupload_maxk($qp_input),
        'write_upload' => qp_write_upload($qp_input),
        default => throw new InvalidArgumentException("unknown operation $qp_mode"),
    };
    qp_write($qp_output_file, ['out' => $qp_out]);
} catch (Throwable $qp_failure) {
    qp_write($qp_output_file, ['error' => get_class($qp_failure) . ': ' . $qp_failure->getMessage()]);
    fwrite(STDERR, $qp_failure . "\n");
    exit(1);
}
