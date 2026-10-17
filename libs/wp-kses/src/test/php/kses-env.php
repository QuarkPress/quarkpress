<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Loads WordPress's kses.php with what it needs (formatting.php, the HTML API, the block parser)
 * on top of the oracle bootstrap (or export-tables.php, which defines the same functions), plus the
 * remaining environment functions as fixed stubs. qp_kses_mode() switches WordPress's default
 * kses-related filters on ('wp') or off ('bare').
 */

const QP_KSES_UPLOAD_URL = 'https://example.org/wp-content/uploads/2026/10';

function _deprecated_argument($function_name, $version, $message = '')
{
}

function _x($text, $context, $domain = 'default')
{
    return $text;
}

function is_admin()
{
    return false;
}

function wp_json_encode($value, $flags = 0, $depth = 512)
{
    return json_encode($value, $flags, $depth);
}

function get_post($post = null)
{
    return null;
}

function wp_upload_dir($time = null, $create_dir = true, $refresh_cache = false)
{
    return ['url' => QP_KSES_UPLOAD_URL];
}

function wp_parse_url($url, $component = -1)
{
    return parse_url($url, $component);
}

function current_user_can($capability, ...$args)
{
    return false;
}

require QP_WP . 'compat.php';
require QP_WP . 'formatting.php';
require QP_WP . 'class-wp-token-map.php';
foreach (['class-wp-html-attribute-token', 'class-wp-html-span', 'class-wp-html-text-replacement',
    'class-wp-html-decoder', 'class-wp-html-doctype-info', 'class-wp-html-unsupported-exception',
    'class-wp-html-tag-processor'] as $qp_file) {
    require QP_WP . 'html-api/' . $qp_file . '.php';
}
require QP_WP . 'html-api/html5-named-character-references.php';
require QP_WP . 'kses.php';
require QP_WP . 'class-wp-block-parser.php';
require QP_WP . 'blocks.php';

/** 'wp' registers WordPress's default kses-related filters (default-filters.php); 'bare' removes them. */
function qp_kses_mode(string $mode): void
{
    remove_filter('pre_kses', 'wp_pre_kses_less_than');
    remove_filter('pre_kses', 'wp_pre_kses_block_attributes', 10);
    remove_filter('wp_kses_allowed_html', '_wp_kses_allow_note_mention_span', 10);
    if ('wp' === $mode) {
        add_filter('pre_kses', 'wp_pre_kses_less_than');
        add_filter('pre_kses', 'wp_pre_kses_block_attributes', 10, 3);
        add_filter('wp_kses_allowed_html', '_wp_kses_allow_note_mention_span', 10, 2);
    } elseif ('bare' !== $mode) {
        throw new InvalidArgumentException('mode must be bare or wp, not ' . $mode);
    }
}

/** The safe_style_css list of the current case, or null for WordPress's own list. */
$GLOBALS['qp_css_properties'] = null;
add_filter('safe_style_css', function ($properties) {
    return $GLOBALS['qp_css_properties'] ?? $properties;
}, PHP_INT_MAX);

/** WordPress's data tables, for comparing with the Java ones. */
function qp_tables(): array
{
    global $allowedentitynames, $allowedxmlentitynames;
    $css = null;
    $capture = function ($properties) use (&$css) {
        $css = $properties;
        return $properties;
    };
    add_filter('safe_style_css', $capture, PHP_INT_MAX - 1);
    safecss_filter_attr('color:red');
    remove_filter('safe_style_css', $capture, PHP_INT_MAX - 1);
    $tables = [];
    foreach (['post', 'data', 'user_description', 'pre_user_description', 'pre_term_description', 'strip',
        'pre_comment_content', 'title_save_pre'] as $context) {
        $tables['context:' . $context] = wp_kses_allowed_html($context);
    }
    $tables['entities'] = $allowedentitynames;
    $tables['xml_entities'] = $allowedxmlentitynames;
    $tables['uri_attributes'] = wp_kses_uri_attributes();
    $tables['protocols'] = wp_allowed_protocols();
    $tables['safe_style_css'] = $css;
    return $tables;
}

/** Runs one case: {"fn": ..., plus that function's arguments}. Field names match the Java Examples runner. */
function qp_kses_case(array $in): mixed
{
    qp_kses_mode($in['mode'] ?? 'bare');
    $GLOBALS['qp_css_properties'] = $in['properties'] ?? null;
    $protocols = $in['protocols'] ?? [];
    $text = $in['text'] ?? '';
    return match ($in['fn']) {
        'no_null' => wp_kses_no_null($text, $in['keep'] ? ['slash_zero' => 'keep'] : null),
        'stripslashes' => wp_kses_stripslashes($text),
        'html_error' => wp_kses_html_error($text),
        'decode_attribute' => WP_HTML_Decoder::decode_attribute($text),
        'normalize_entities' => wp_kses_normalize_entities($text, $in['xml'] ? 'xml' : 'html'),
        'esc_attr' => esc_attr($text),
        'bad_protocol' => wp_kses_bad_protocol($text, [] === $protocols ? wp_allowed_protocols() : $protocols),
        'hair' => wp_kses_hair($text, wp_allowed_protocols()),
        'safecss' => safecss_filter_attr($text),
        'kses' => wp_kses($text, $in['allowed'] ?? $in['context'], $protocols),
        'one_attr' => wp_kses_one_attr($text, $in['element']),
        'strip_all_tags' => wp_strip_all_tags($text, $in['remove_breaks']),
        'tables' => qp_tables(),
        default => throw new InvalidArgumentException('unknown fn ' . $in['fn']),
    };
}
