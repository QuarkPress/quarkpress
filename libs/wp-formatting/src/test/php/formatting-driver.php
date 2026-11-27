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

qw_run(function (array $in): array {
    $GLOBALS['qw_options'] = [];
    $GLOBALS['qw_word_count_type'] = 'words';
    $args = $in['args'] ?? [];
    return ['out' => match ($in['fn']) {
        'html_split' => wp_html_split($in['in']),
        'replace_in_html_tags' => wp_replace_in_html_tags($in['in'], $args['pairs']),
    }];
});
