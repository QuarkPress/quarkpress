<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the save path of the posts module. It always loads all of formatting.php, kses, the HTML API
 * and the block code, so it does not depend on which WordPress files other tests have fetched.
 *   {"op":"strip_custom_css","text":...}                             wp_strip_custom_css_from_blocks on the slashed
 *                                                                    text, unslashed again
 *   {"op":"convert_invalid_entities","text":...}                     convert_invalid_entities(text)
 *   {"op":"save_filter","field":title|content|excerpt,"filtered":bool,"text":...}
 *                                                                    the unslashed result of the field's save filters
 *                                                                    (title_save_pre, content_save_pre,
 *                                                                    excerpt_save_pre) on the slashed text, with
 *                                                                    WordPress's default filters at their priorities
 *                                                                    for a caller with ("filtered") or without kses
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';
qw_load_functions(QW_WP . 'functions.php', [
    'mbstring_binary_safe_encoding', 'reset_mbstring_encoding', 'wp_is_numeric_array', 'wp_parse_args',
    'wp_json_encode', '_wp_json_sanity_check', '_wp_json_convert_string',
]);

function _deprecated_argument($function_name, $version, $message = '')
{
}

function get_post($post = null)
{
    return null;
}

function wp_upload_dir($time = null, $create_dir = true, $refresh_cache = false)
{
    return ['url' => 'https://example.org/wp-content/uploads/2026/10'];
}

function wp_parse_url($url, $component = -1)
{
    return parse_url($url, $component);
}

function current_user_can($capability, ...$args)
{
    return false;
}

require QW_WP . 'compat.php';
require QW_WP . 'formatting.php';
require QW_WP . 'class-wp-token-map.php';
foreach (['class-wp-html-attribute-token', 'class-wp-html-span', 'class-wp-html-text-replacement',
    'class-wp-html-decoder', 'class-wp-html-doctype-info', 'class-wp-html-unsupported-exception',
    'class-wp-html-tag-processor'] as $qw_file) {
    require QW_WP . 'html-api/' . $qw_file . '.php';
}
require QW_WP . 'html-api/html5-named-character-references.php';
require QW_WP . 'kses.php';
require QW_WP . 'class-wp-block-parser-block.php';
require QW_WP . 'class-wp-block-parser-frame.php';
require QW_WP . 'class-wp-block-parser.php';
require QW_WP . 'blocks.php';
qw_load_functions(QW_WP . 'block-supports/custom-css.php', ['wp_strip_custom_css_from_blocks']);

function _x($text, $context, $domain = 'default')
{
    return $text;
}

function is_admin()
{
    return false;
}

/**
 * Rule applied by the driver: WordPress's default save filters at their real priorities. Callers with kses get the
 * kses filters and the custom CSS stripping; the others only the filters that apply to everyone. The global styles
 * filter (priority 9) is left out (a planned difference).
 */
function qw_save_filters(bool $filtered): void
{
    foreach (['title_save_pre', 'content_save_pre', 'excerpt_save_pre', 'pre_kses', 'wp_kses_allowed_html'] as $tag) {
        remove_all_filters($tag);
    }
    add_filter('title_save_pre', 'trim');
    add_filter('content_save_pre', 'convert_invalid_entities');
    add_filter('excerpt_save_pre', 'convert_invalid_entities');
    add_filter('content_save_pre', 'balanceTags', 50);
    add_filter('excerpt_save_pre', 'balanceTags', 50);
    add_filter('pre_kses', 'wp_pre_kses_less_than');
    add_filter('pre_kses', 'wp_pre_kses_block_attributes', 10, 3);
    add_filter('wp_kses_allowed_html', '_wp_kses_allow_note_mention_span', 10, 2);
    if ($filtered) {
        add_filter('title_save_pre', 'wp_filter_kses');
        add_filter('content_save_pre', 'wp_strip_custom_css_from_blocks', 8);
        add_filter('content_save_pre', 'wp_filter_post_kses');
        add_filter('excerpt_save_pre', 'wp_filter_post_kses');
    }
}

qw_run(function (array $in): array {
    $GLOBALS['qw_options'] = $in['options'] ?? [];
    switch ($in['op']) {
        case 'strip_custom_css':
            // Driver-applied rule: WordPress's content filters receive slashed text; the result is unslashed.
            return ['value' => stripslashes(wp_strip_custom_css_from_blocks(addslashes($in['text'])))];
        case 'convert_invalid_entities':
            return ['value' => convert_invalid_entities($in['text'])];
        case 'save_filter':
            qw_save_filters((bool) $in['filtered']);
            // Driver-applied rule: wp_insert_post hands the filters slashed text and unslashes what they return.
            return ['value' => stripslashes(apply_filters($in['field'] . '_save_pre', addslashes($in['text'])))];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
