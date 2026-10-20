<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the posts module.
 *   {"op":"sanitize_title","text":...,"fallback":...,"context":...}  sanitize_title(text, fallback, context)
 *   {"op":"truncate","slug":...,"length":...}                        _truncate_post_slug(slug, length)
 *   {"op":"sanitize_key","text":...}                                 sanitize_key(text)
 *   {"op":"urlencode","text":...}                                    strtolower(urlencode(text))
 *   {"op":"post_type","name":...,"args":{...}}                       a WP_Post_Type built from the arguments, with its
 *                                                                    supports added; its flags, labels and supports
 *   {"op":"post_status","name":...,"args":{...}}                     register_post_status(name, args); its flags
 *   {"op":"gmt_from_date","date":...,"options":{...}}                get_gmt_from_date(date) with the site options given
 *   {"op":"date_from_gmt","date":...,"options":{...}}                get_date_from_gmt(date) with the site options given
 *   {"op":"valid_date","date":...}                                 whether wp_resolve_post_date accepts the date text
 *   {"op":"intl"}                                                    whether this PHP has the intl extension
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', [
    'mbstring_binary_safe_encoding', 'reset_mbstring_encoding', 'wp_is_numeric_array', 'wp_parse_args',
    'wp_timezone_string', 'wp_timezone', 'wp_checkdate',
]);
qp_load_functions(QP_WP . 'formatting.php', [
    'sanitize_title', 'sanitize_title_with_dashes', 'sanitize_title_for_query', 'utf8_uri_encode', 'remove_accents',
    'sanitize_key', 'get_gmt_from_date', 'get_date_from_gmt',
]);
qp_load_functions(QP_WP . 'post.php', [
    '_truncate_post_slug', 'register_post_status', 'get_post_type_labels', '_get_custom_object_labels',
    'get_post_type_capabilities', '_post_type_meta_capabilities', 'add_post_type_support', 'post_type_supports',
    'get_all_post_type_supports', 'wp_resolve_post_date',
]);
require QP_WP . 'class-wp-post-type.php';

/** Stub: the date the post-date check falls back to for an empty or zero date; fixed, so the answer is stable. */
function current_time($type, $gmt = 0)
{
    return '2020-01-01 00:00:00';
}

/** WordPress's default locale. */
function get_locale()
{
    return 'en_US';
}

function _x($text, $context, $domain = 'default')
{
    return $text;
}

function _n_noop($singular, $plural, $domain = null)
{
    return ['singular' => $singular, 'plural' => $plural, 'context' => null, 'domain' => $domain];
}

function is_admin()
{
    return false;
}

// WordPress's default filters attach the dashes sanitizer to sanitize_title.
add_filter('sanitize_title', 'sanitize_title_with_dashes', 10, 3);

/** Rule applied by the driver: flags are compared as booleans, whatever WordPress stores (0, 1, '', true). */
function qp_bool(mixed $value): bool
{
    return (bool) $value;
}

qp_run(function (array $in): array {
    $GLOBALS['qp_options'] = $in['options'] ?? [];
    switch ($in['op']) {
        case 'sanitize_title':
            return ['value' => sanitize_title($in['text'], $in['fallback'] ?? '', $in['context'] ?? 'save')];
        case 'truncate':
            return ['value' => _truncate_post_slug($in['slug'], (int) $in['length'])];
        case 'sanitize_key':
            return ['value' => sanitize_key($in['text'])];
        case 'urlencode':
            return ['value' => strtolower(urlencode($in['text']))];
        case 'post_type':
            // Rules applied by the driver: start from empty registries, as a fresh request has them.
            $GLOBALS['_wp_post_type_features'] = [];
            $GLOBALS['post_type_meta_caps'] = [];
            WP_Post_Type::reset_default_labels();
            // Rule applied by the driver: register_post_type sanitizes the name with sanitize_key before building the
            // object; this driver builds the object directly, so it does that step itself.
            $type = new WP_Post_Type(sanitize_key($in['name']), $in['args']);
            $type->add_supports();
            // Rule applied by the driver (planned normalization): WordPress keeps unset labels as null (a flat type's
            // parent_item_colon); the comparison drops null-valued keys, and QuarkPress omits them.
            $labels = array_filter((array) $type->labels, static fn($value) => null !== $value);
            return [
                'name' => $type->name,
                'public' => qp_bool($type->public),
                'hierarchical' => qp_bool($type->hierarchical),
                'exclude_from_search' => qp_bool($type->exclude_from_search),
                'publicly_queryable' => qp_bool($type->publicly_queryable),
                'embeddable' => qp_bool($type->embeddable),
                'show_ui' => qp_bool($type->show_ui),
                'show_in_menu' => qp_bool($type->show_in_menu),
                'show_in_nav_menus' => qp_bool($type->show_in_nav_menus),
                'show_in_admin_bar' => qp_bool($type->show_in_admin_bar),
                'map_meta_cap' => qp_bool($type->map_meta_cap),
                'capability_type' => $type->capability_type,
                'edit_posts' => $type->cap->edit_posts,
                'supports' => array_keys(get_all_post_type_supports($type->name)),
                'labels' => $labels,
            ];
        case 'post_status':
            $GLOBALS['wp_post_statuses'] = [];
            $status = register_post_status($in['name'], $in['args']);
            return [
                'name' => $status->name,
                'label' => $status->label,
                'label_count' => [$status->label_count['singular'], $status->label_count['plural']],
                'public' => qp_bool($status->public),
                'protected' => qp_bool($status->protected),
                'private' => qp_bool($status->private),
                'internal' => qp_bool($status->internal),
                'publicly_queryable' => qp_bool($status->publicly_queryable),
                'exclude_from_search' => qp_bool($status->exclude_from_search),
                'show_in_admin_all_list' => qp_bool($status->show_in_admin_all_list),
                'show_in_admin_status_list' => qp_bool($status->show_in_admin_status_list),
                'date_floating' => qp_bool($status->date_floating),
            ];
        case 'gmt_from_date':
            return ['value' => get_gmt_from_date($in['date'])];
        case 'date_from_gmt':
            return ['value' => get_date_from_gmt($in['date'])];
        case 'valid_date':
            return ['value' => false === wp_resolve_post_date($in['date'], '') ? 'invalid' : 'valid'];
        case 'intl':
            return ['value' => extension_loaded('intl') ? 'yes' : 'no'];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
