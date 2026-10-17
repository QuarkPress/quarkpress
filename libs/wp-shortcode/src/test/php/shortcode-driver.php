<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-shortcode. A case is either {"allowlist": true}, which returns the "post" kses
 * allowlist, or {"content", "profile", "hooks", "aspects"}: the driver registers the named profile's
 * shortcodes and hooks, then runs the WordPress shortcode function behind each aspect on the content.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
require QP_WP . 'compat.php';
require QP_WP . 'formatting.php';
require QP_WP . 'kses.php';
require QP_WP . 'shortcodes.php';
require QP_WP . 'class-wp-token-map.php';
require QP_WP . 'html-api/html5-named-character-references.php';
require QP_WP . 'html-api/class-wp-html-decoder.php';

/** Environment stub: kses's PDF object rule asks for the upload directory. */
function wp_upload_dir(): array
{
    return ['baseurl' => 'https://example.org/wp-content/uploads', 'basedir' => '/nonexistent', 'error' => false];
}

const QP_SHORTCODE_JSON = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE;
const QP_PAIRS = ['a' => 'A', 'b' => 'B', '0' => 'Z', 'data-x' => 'D'];

/** {TAG:ATTRS:CONTENT}, with ATTRS and CONTENT as JSON. */
function qp_echo(array $atts, string $content, string $tag): string
{
    return '{' . $tag . ':' . json_encode($atts, QP_SHORTCODE_JSON) . ':' . json_encode($content, QP_SHORTCODE_JSON) . '}';
}

function qp_profile(string $name): void
{
    remove_all_shortcodes();
    $tags = match ($name) {
        'main' => [
            'foo' => 'qp_echo', 'b' => 'qp_echo', 'foo-bar' => 'qp_echo', 'foo_bar' => 'qp_echo',
            'x.y' => 'qp_echo', 'ü' => 'qp_echo', 'gallery' => 'qp_echo',
            'inner' => static fn() => 'I',
            'outer' => static fn($atts, $content) => '<o>' . do_shortcode($content) . '</o>',
            'quiet' => static fn($atts, $content) => '<o>' . $content . '</o>',
            'raw' => static fn($atts, $content) => 'C=' . $content,
            'emit' => static fn() => '[inner]',
            'ent' => static fn() => '&#91;inner&#93;',
            'nul' => static fn() => null,
            'cls' => static fn() => 'red',
            'url' => static fn() => 'http://ex.com/?q="x"&y=<z>',
            'js' => static fn() => 'javascript:alert(1)',
            'attr' => static fn() => 'title="hi"',
            'amp' => static fn() => 'a &amp; b &copy; &apos; &bogus; &#65; &#x41;',
        ],
        'x-first' => ['x' => 'qp_echo', 'x.y' => 'qp_echo'],
        'xy-first' => ['x.y' => 'qp_echo', 'x' => 'qp_echo'],
        'empty' => [],
    };
    foreach ($tags as $tag => $callback) {
        add_shortcode((string) $tag, $callback);
    }
}

function qp_hooks(array $hooks): void
{
    remove_all_filters('pre_do_shortcode_tag');
    remove_all_filters('do_shortcode_tag');
    remove_all_filters('strip_shortcodes_tagnames');
    remove_all_filters('shortcode_atts_gal');
    add_filter('shortcode_atts_gal', static function (array $out): array {
        $out['extra'] = 'E';
        return $out;
    }, 10, 1);
    foreach ($hooks as $hook) {
        match ($hook) {
            'pre-inner' => add_filter('pre_do_shortcode_tag', static fn($value, $tag) => 'inner' === $tag ? 'PRE' : $value, 10, 2),
            'post-wrap' => add_filter('do_shortcode_tag', static fn($output, $tag) => '<' . $tag . ':' . $output . '>', 10, 2),
            'strip-foo' => add_filter('strip_shortcodes_tagnames', static fn() => ['foo', 'unreg'], 10, 0),
        };
    }
}

/** has_shortcode for every registered tag. */
function qp_has(string $content): array
{
    $found = [];
    foreach (array_keys($GLOBALS['shortcode_tags']) as $tag) {
        $found[(string) $tag] = has_shortcode($content, (string) $tag);
    }
    return $found;
}

/** shortcode_parse_atts as [key type, key, value] triples, so integer and string keys stay apart. */
function qp_atts(array $atts): array
{
    $entries = [];
    foreach ($atts as $key => $value) {
        $entries[] = [is_int($key) ? 'i' : 's', (string) $key, $value];
    }
    return $entries;
}

/** shortcode_atts with fixed defaults, as "key=value" lines in result order. */
function qp_defaults(string $content): array
{
    $lines = [];
    foreach (shortcode_atts(QP_PAIRS, shortcode_parse_atts($content), 'gal') as $key => $value) {
        $lines[] = $key . '=' . $value;
    }
    return $lines;
}

/** wp_kses_allowed_html('post') in the notation of the Java table: element => sorted rule names. */
function qp_allowlist(): array
{
    $table = [];
    foreach (wp_kses_allowed_html('post') as $element => $attributes) {
        $rules = [];
        foreach ($attributes as $name => $rule) {
            $rules[] = qp_rule((string) $name, $rule);
        }
        sort($rules, SORT_STRING);
        $table[(string) $element] = $rules;
    }
    ksort($table, SORT_STRING);
    return $table;
}

function qp_rule(string $name, mixed $rule): string
{
    return match (true) {
        true === $rule => $name,
        ['valueless' => 'y'] === $rule => $name . '(valueless)',
        ['required' => true, 'value_callback' => '_wp_kses_allow_pdf_objects'] === $rule => $name . '(pdf-object)',
        is_array($rule) && ['required', 'values'] === array_keys($rule) && true === $rule['required']
            => $name . '(values:' . implode(',', $rule['values']) . ')',
        default => $name . '(?' . json_encode($rule) . ')',
    };
}

qp_run(function (array $in): array {
    if (isset($in['allowlist'])) {
        return qp_allowlist();
    }
    qp_profile($in['profile']);
    qp_hooks($in['hooks']);
    $content = $in['content'];
    $out = [];
    foreach ($in['aspects'] as $aspect) {
        $out[$aspect] = match ($aspect) {
            'apply' => do_shortcode($content),
            'apply_ignore_html' => do_shortcode($content, true),
            'strip' => strip_shortcodes($content),
            'tags' => get_shortcode_tags_in_content($content),
            'has' => qp_has($content),
            'unautop' => shortcode_unautop($content),
            'atts' => qp_atts(shortcode_parse_atts($content)),
            'defaults' => qp_defaults($content),
        };
    }
    return $out;
});
