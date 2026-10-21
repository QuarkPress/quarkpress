<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the taxonomy module.
 *   {"op":"taxonomy","name":...,"object_types":[...],"args":{...}}     a WP_Taxonomy built from the arguments on a
 *                                                                    front-end request with plain permalinks: its
 *                                                                    flags, capability names and labels
 *   {"op":"term_field","field":name|description|slug,"taxonomy":...,"text":...}
 *                                                                    sanitize_term_field(field, slashed text, 0,
 *                                                                    taxonomy, 'db') with WordPress's default term
 *                                                                    save filters at their priorities; a name or
 *                                                                    description is unslashed, a slug is not
 *   {"op":"name_and_slug","text":...}                                the stored name of the text and the slug
 *                                                                    sanitize_title generates from it
 *   {"op":"text_field","text":...}                                   sanitize_text_field(text)
 *   {"op":"strtolower","text":...}                                   strtolower(text)
 *   {"op":"parse_order","order":...}                                 WP_Term_Query::parse_order(order)
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', [
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

function _x($text, $context, $domain = 'default')
{
    return $text;
}

function is_admin()
{
    return false;
}

/** WordPress's default locale (remove_accents asks for it on non-ASCII text). */
function get_locale()
{
    return 'en_US';
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
require QP_WP . 'class-wp-block-parser-block.php';
require QP_WP . 'class-wp-block-parser-frame.php';
require QP_WP . 'class-wp-block-parser.php';
require QP_WP . 'blocks.php';
qp_load_functions(QP_WP . 'post.php', ['_get_custom_object_labels']);
qp_load_functions(QP_WP . 'taxonomy.php', ['sanitize_term_field', 'get_taxonomy_labels']);
require QP_WP . 'class-wp-taxonomy.php';
require QP_WP . 'class-wp-term-query.php';

/** Exposes the protected order normalization. */
final class QpTermQuery extends WP_Term_Query
{
    public function order($order): string
    {
        return $this->parse_order($order);
    }
}

/**
 * Rule applied by the driver: WordPress's default save filters of term fields at their real priorities (they do not
 * depend on the current user), with kses' own pre-filters.
 */
function qp_term_filters(): void
{
    foreach (['pre_term_name', 'pre_term_description', 'pre_term_slug', 'sanitize_title', 'pre_kses',
                 'wp_kses_allowed_html'] as $tag) {
        remove_all_filters($tag);
    }
    add_filter('pre_term_name', 'sanitize_text_field');
    add_filter('pre_term_name', 'wp_filter_kses');
    add_filter('pre_term_name', '_wp_specialchars', 30);
    add_filter('pre_term_description', 'wp_filter_kses');
    add_filter('pre_term_slug', 'sanitize_title');
    add_filter('sanitize_title', 'sanitize_title_with_dashes', 10, 3);
    add_filter('pre_kses', 'wp_pre_kses_less_than');
    add_filter('pre_kses', 'wp_pre_kses_block_attributes', 10, 3);
    add_filter('wp_kses_allowed_html', '_wp_kses_allow_note_mention_span', 10, 2);
}

function qp_db_field(string $field, string $taxonomy, string $text): string
{
    // Rule applied by the driver: wp_insert_term hands sanitize_term slashed values; it unslashes the name and the
    // description it gets back and keeps the slug as returned.
    $value = sanitize_term_field($field, wp_slash($text), 0, $taxonomy, 'db');
    return 'slug' === $field ? $value : wp_unslash($value);
}

/** Rule applied by the driver: flags are compared as booleans, whatever WordPress stores (0, 1, '', true). */
function qp_bool(mixed $value): bool
{
    return (bool) $value;
}

qp_run(function (array $in): array {
    // Rule applied by the driver: plain permalinks, so the taxonomy's rewrite arguments do not depend on options.
    $GLOBALS['qp_options'] = ['permalink_structure' => ''];
    switch ($in['op']) {
        case 'taxonomy':
            WP_Taxonomy::reset_default_labels();
            $taxonomy = new WP_Taxonomy($in['name'], $in['object_types'], $in['args']);
            // Rule applied by the driver (planned normalization): WordPress keeps the labels that do not apply to the
            // kind as null; they are dropped, and QuarkPress omits them.
            $labels = array_filter((array) $taxonomy->labels, static fn($value) => null !== $value);
            return [
                'name' => $taxonomy->name,
                'object_type' => array_values($taxonomy->object_type),
                'public' => qp_bool($taxonomy->public),
                'publicly_queryable' => qp_bool($taxonomy->publicly_queryable),
                'hierarchical' => qp_bool($taxonomy->hierarchical),
                'show_ui' => qp_bool($taxonomy->show_ui),
                'show_in_menu' => qp_bool($taxonomy->show_in_menu),
                'show_in_nav_menus' => qp_bool($taxonomy->show_in_nav_menus),
                'show_tagcloud' => qp_bool($taxonomy->show_tagcloud),
                'show_in_quick_edit' => qp_bool($taxonomy->show_in_quick_edit),
                'show_admin_column' => qp_bool($taxonomy->show_admin_column),
                'cap' => (array) $taxonomy->cap,
                'show_in_rest' => qp_bool($taxonomy->show_in_rest),
                // Rule applied by the driver: no REST base (false) is written as null.
                'rest_base' => false === $taxonomy->rest_base ? null : $taxonomy->rest_base,
                'default_term' => $taxonomy->default_term,
                'sort' => qp_bool($taxonomy->sort),
                'labels' => $labels,
            ];
        case 'term_field':
            qp_term_filters();
            return ['value' => qp_db_field($in['field'], $in['taxonomy'], $in['text'])];
        case 'name_and_slug':
            qp_term_filters();
            $name = qp_db_field('name', 'post_tag', $in['text']);
            return ['name' => $name, 'slug' => sanitize_title($name)];
        case 'text_field':
            return ['value' => sanitize_text_field($in['text'])];
        case 'strtolower':
            return ['value' => strtolower($in['text'])];
        case 'parse_order':
            return ['value' => (new QpTermQuery())->order($in['order'])];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
