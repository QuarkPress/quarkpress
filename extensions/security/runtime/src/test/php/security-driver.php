<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the security module.
 *   {"op":"sanitize_user","text":...}   sanitize_user(text, strict = true)
 *   {"op":"remove_accents","text":...}  remove_accents(text) with the site locale en_US
 *   {"op":"nicename","text":...}        the nicename wp_insert_user gives a new user registering with the login text,
 *                                     and whether wp_insert_user refuses the user (empty or over-60-character login, or empty nicename);
 *                                     the steps of wp_insert_user it needs are marked where the driver applies them
 *   {"op":"intl"}                       whether this PHP has the intl extension
 *   {"op":"map_meta_cap",...}           map_meta_cap(cap, user, ...args), with the functions it reads answered from the
 *                                     case: posts, post types, statuses, trash status, terms, taxonomies, options,
 *                                     network options, the network flag, super admins and user_can answers
 *   {"op":"post_type_caps",...}         get_post_type_capabilities of a post type with the given capability type,
 *                                     map_meta_cap flag and capabilities
 *   {"op":"roles"}                    the role table populate_roles stores, read back from the options
 * With QP_DISALLOW_UNFILTERED_HTML set, the DISALLOW_UNFILTERED_HTML constant is defined true.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
if (false !== getenv('QP_DISALLOW_UNFILTERED_HTML')) {
    define('DISALLOW_UNFILTERED_HTML', true);
}
qp_load_functions(QP_WP . 'functions.php', [
    'mbstring_binary_safe_encoding', 'reset_mbstring_encoding', 'wp_is_numeric_array',
]);
qp_load_functions(QP_WP . 'formatting.php', [
    'sanitize_user', 'remove_accents', 'wp_strip_all_tags', 'sanitize_title', 'sanitize_title_with_dashes',
    'utf8_uri_encode',
]);
qp_load_functions(QP_WP . 'capabilities.php', ['map_meta_cap', 'add_role', 'get_role', 'wp_roles']);
// WordPress's own get_post_status (attachments inherit from their parent) and get_post_status_object, which reads
// the $wp_post_statuses global the case fills.
qp_load_functions(QP_WP . 'post.php', [
    'get_post_type_capabilities', '_post_type_meta_capabilities', 'get_post_status', 'get_post_status_object',
]);
require QP_WP . 'class-wp-role.php';
require QP_WP . 'class-wp-roles.php';
qp_load_functions(ABSPATH . 'wp-admin/includes/schema.php', [
    'populate_roles', 'populate_roles_160', 'populate_roles_210', 'populate_roles_230', 'populate_roles_250',
    'populate_roles_260', 'populate_roles_270', 'populate_roles_280', 'populate_roles_300',
]);

/** WordPress's default locale. */
function get_locale()
{
    return 'en_US';
}

// WordPress's default filters attach the dashes sanitizer to sanitize_title.
add_filter('sanitize_title', 'sanitize_title_with_dashes', 10, 3);

$GLOBALS['qp_case'] = [];

function qp_case(string $key, mixed $default = null): mixed
{
    return $GLOBALS['qp_case'][$key] ?? $default;
}

function is_multisite()
{
    return (bool) qp_case('multisite', false);
}

function is_super_admin($user_id = false)
{
    return in_array((int) $user_id, qp_case('super_admins', []), true);
}

function user_can($user, $capability, ...$args)
{
    return in_array($capability, qp_case('user_can', []), true);
}

function get_site_option($option, $default_value = false, $deprecated = true)
{
    $options = qp_case('network_options', []);
    return array_key_exists($option, $options) ? $options[$option] : $default_value;
}

function update_option($option, $value, $autoload = null)
{
    $GLOBALS['qp_options'][$option] = $value;
    return true;
}

function get_current_blog_id()
{
    return 1;
}

/** QuarkPress edits no files: file modifications count as allowed, as in a default WordPress. */
function wp_is_file_mod_allowed($context)
{
    return true;
}

function is_wp_error($thing)
{
    return $thing instanceof WP_Error;
}

/** The posts of the case, by ID: the post a capability is checked against and any parent it has. */
function get_post($post = null, $output = 'OBJECT', $filter = 'raw')
{
    $id = is_object($post) ? (int) $post->ID : (int) $post;
    foreach (qp_case('posts', []) as $found) {
        if ($id === (int) $found['ID']) {
            return (object) $found;
        }
    }
    return null;
}

function get_post_type_object($post_type)
{
    $type = qp_case('post_types', [])[$post_type] ?? null;
    return null === $type ? null : (object) ['cap' => (object) $type['cap'], 'map_meta_cap' => $type['map_meta_cap']];
}

function get_post_meta($post_id, $key = '', $single = false)
{
    if ('_wp_trash_meta_status' === $key) {
        $found = get_post($post_id);
        return null === $found ? '' : $found->trash_status;
    }
    return '';
}

function get_term($term, $taxonomy = '', $output = 'OBJECT', $filter = 'raw')
{
    $found = qp_case('term');
    if (null === $found || (int) $term !== (int) $found['term_id']) {
        return null;
    }
    return (object) $found;
}

function get_taxonomy($taxonomy)
{
    $found = qp_case('taxonomies', [])[$taxonomy] ?? null;
    return null === $found ? false : (object) ['cap' => (object) $found];
}

final class QpWpdb
{
    public string $prefix = 'wp_';

    public function get_blog_prefix($blog_id = null): string
    {
        return 'wp_';
    }
}

$wpdb = new QpWpdb();

qp_run(function (array $in): array {
    $GLOBALS['qp_case'] = $in;
    $GLOBALS['qp_options'] = $in['options'] ?? [];
    $GLOBALS['wp_post_statuses'] = array_map(fn($status) => (object) $status, $in['statuses'] ?? []);
    switch ($in['op']) {
        case 'sanitize_user':
            return ['value' => sanitize_user($in['text'], true)];
        case 'remove_accents':
            return ['value' => remove_accents($in['text'])];
        case 'nicename':
            // Driver-applied rule 1 (wp_insert_user): the login is the strictly sanitized name, trimmed.
            $login = trim(sanitize_user($in['text'], true));
            // Driver-applied rule 2 (wp_insert_user): a new user's nicename starts from the first 50 characters of
            // the login; the login is ASCII here, so substr equals WordPress's mb_substr.
            $nicename = sanitize_title(substr($login, 0, 50));
            // Driver-applied rule 3 (wp_insert_user): an empty login, a login over 60 characters (user_login_too_long)
            // or an empty nicename is an error.
            return ['value' => $nicename, 'refused' => empty($login) || mb_strlen($login) > 60 || empty($nicename)];
        case 'intl':
            return ['value' => extension_loaded('intl') ? 'yes' : 'no'];
        case 'map_meta_cap':
            // Driver-applied rule 3: the registered post types' meta capability names, which register_post_type
            // would have recorded.
            $GLOBALS['post_type_meta_caps'] = $in['meta_caps'] ?? [];
            return ['caps' => array_values(map_meta_cap($in['cap'], (int) $in['user'], ...$in['args']))];
        case 'post_type_caps':
            $type = (object) [
                'capability_type' => [$in['singular'], $in['plural']],
                'map_meta_cap' => $in['map_meta_cap'],
                'capabilities' => $in['capabilities'],
            ];
            return ['caps' => (array) get_post_type_capabilities($type)];
        case 'roles':
            $GLOBALS['wp_roles'] = null;
            populate_roles();
            return ['roles' => get_option('wp_user_roles')];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
