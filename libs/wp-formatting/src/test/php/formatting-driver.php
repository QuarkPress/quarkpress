<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-formatting. A case is {"fn", "in", "args"}: the driver resets the per-case options,
 * runs the WordPress formatting function named by "fn" on "in" with the arguments in "args", and returns
 * {"out": result}.
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';
require QW_WP . 'compat.php';
require QW_WP . 'formatting.php';
require QW_WP . 'kses.php';
require QW_WP . 'shortcodes.php';
require QW_WP . 'class-wp-token-map.php';
require QW_WP . 'html-api/html5-named-character-references.php';
require QW_WP . 'html-api/class-wp-html-decoder.php';

/** Environment stub: translation with context returns the English text. */
function _x($text, $context, $domain = 'default')
{
    return $text;
}

/** Environment stub: the word-count type a locale would give; a case may set it. */
function wp_get_word_count_type()
{
    return $GLOBALS['qw_word_count_type'] ?? 'words';
}

/** Environment stub: the site URL from the case's "siteurl" option. */
function site_url($path = '', $scheme = null)
{
    return rtrim((string) get_option('siteurl', ''), '/') . $path;
}

/** Environment stub: the wp-includes URL below the site URL. */
function includes_url($path = '', $scheme = null)
{
    $url = site_url('/' . WPINC . '/', $scheme);
    if ($path && is_string($path)) {
        $url .= ltrim($path, '/');
    }
    return $url;
}

/** Core's registered shortcode names; a texturize case may give its own list in "shortcodes". */
const QW_CORE_SHORTCODES = ['wp_caption', 'caption', 'gallery', 'playlist', 'audio', 'video', 'embed'];

/**
 * wptexturize with the case's shortcode registry, cockney map and no-texturize lists, tables rebuilt. null means
 * WordPress failed: its split hit the PCRE backtrack limit and PHP threw a TypeError.
 */
function qw_texturize(string $text, array $args): ?string
{
    $GLOBALS['shortcode_tags'] = [];
    foreach ($args['shortcodes'] ?? QW_CORE_SHORTCODES as $name) {
        $GLOBALS['shortcode_tags'][$name] = static fn() => '';
    }
    if (isset($args['cockney'])) {
        $GLOBALS['wp_cockneyreplace'] = $args['cockney'];
    } else {
        unset($GLOBALS['wp_cockneyreplace']);
    }
    remove_all_filters('no_texturize_tags');
    if (array_key_exists('noTexturizeTags', $args)) {
        $tags = $args['noTexturizeTags'];
        add_filter('no_texturize_tags', static fn() => $tags);
    }
    remove_all_filters('no_texturize_shortcodes');
    if (array_key_exists('noTexturizeShortcodes', $args)) {
        $names = $args['noTexturizeShortcodes'];
        add_filter('no_texturize_shortcodes', static fn() => $names);
    }
    try {
        return wptexturize($text, true);
    } catch (TypeError $e) {
        if (!str_starts_with($e->getMessage(), 'implode():')) {
            throw $e;
        }
        return null;
    }
}

/** capital_P_dangit run as a callback of the named filter, so the real current_filter() selects the mode. */
function qw_capital_p(string $text, string $filter): string
{
    remove_all_filters($filter);
    add_filter($filter, 'capital_P_dangit', 11);
    return apply_filters($filter, $text);
}

/** wptexturize's defaults of the title and content chains: no shortcodes, no cockney map, default lists. */
function qw_texturize_defaults(): void
{
    $GLOBALS['shortcode_tags'] = [];
    unset($GLOBALS['wp_cockneyreplace']);
    remove_all_filters('no_texturize_tags');
    remove_all_filters('no_texturize_shortcodes');
    wptexturize('x', true);
}

/** the_title as core registers it: wptexturize, convert_chars and trim at 10, then capital_P_dangit at 11. */
function qw_title_chain(string $text): string
{
    qw_texturize_defaults();
    remove_all_filters('the_title');
    add_filter('the_title', 'wptexturize');
    add_filter('the_title', 'convert_chars');
    add_filter('the_title', 'trim');
    add_filter('the_title', 'capital_P_dangit', 11);
    return apply_filters('the_title', $text);
}

/** the_content without shortcodes: wpautop and shortcode_unautop at 10, then capital_P_dangit at 11. */
function qw_content_chain(string $text): string
{
    $GLOBALS['shortcode_tags'] = [];
    remove_all_filters('the_content');
    add_filter('the_content', 'wpautop');
    add_filter('the_content', 'shortcode_unautop');
    add_filter('the_content', 'capital_P_dangit', 11);
    return apply_filters('the_content', $text);
}

qw_run(function (array $in): array {
    $GLOBALS['qw_options'] = [];
    $GLOBALS['qw_word_count_type'] = 'words';
    $args = $in['args'] ?? [];
    return ['out' => match ($in['fn']) {
        'html_split' => wp_html_split($in['in']),
        'replace_in_html_tags' => wp_replace_in_html_tags($in['in'], $args['pairs']),
        // Task 2
        'autop' => wpautop($in['in'], $args['br'] ?? true),
        'autop_newline_helper' => _autop_newline_preservation_helper([$in['in']]),
        // Task 3
        'texturize' => qw_texturize($in['in'], $args),
        // Task 4
        'convert_chars' => convert_chars($in['in']),
        'capital_p' => qw_capital_p($in['in'], $args['filter']),
        'title_chain' => qw_title_chain($in['in']),
        'content_chain' => qw_content_chain($in['in']),
    }];
});
