<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Prelude for the command-line helpers (export-tables.php, kses-examples.php): defines the same
 * environment functions as the wp-oracle bootstrap, then loads the hook API and kses-env.php.
 * The first argument is the wordpress-develop/src directory holding the pinned files.
 */

declare(strict_types=1);

if ($argc < 2) {
    fwrite(STDERR, "usage: php " . basename($argv[0]) . " <wordpress-src-dir> [args]\n");
    exit(2);
}
define('ABSPATH', rtrim($argv[1], '/') . '/');
define('WPINC', 'wp-includes');
define('QP_WP', ABSPATH . WPINC . '/');

function __($text, $domain = 'default')
{
    return $text;
}

function _doing_it_wrong($function_name, $message, $version)
{
}

function get_option($option, $default_value = false)
{
    return 'blog_charset' === $option ? 'UTF-8' : $default_value;
}

function _canonical_charset($charset)
{
    return 'UTF-8';
}

function is_utf8_charset($blog_charset = null)
{
    return true;
}

function wp_is_valid_utf8($bytes)
{
    return 1 === preg_match('//u', $bytes);
}

function wp_allowed_protocols()
{
    return ['http', 'https', 'ftp', 'ftps', 'mailto', 'news', 'irc', 'irc6', 'ircs', 'gopher', 'nntp', 'feed',
        'telnet', 'mms', 'rtsp', 'sms', 'svn', 'tel', 'fax', 'xmpp', 'webcal', 'urn'];
}

require QP_WP . 'plugin.php';
require __DIR__ . '/kses-env.php';
