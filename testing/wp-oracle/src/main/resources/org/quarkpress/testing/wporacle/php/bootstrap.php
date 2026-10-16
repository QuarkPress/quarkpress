<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Prelude shared by the QuarkPress oracle drivers. A driver runs as
 *   php <driver> <wordpress-src-dir> <input.jsonl> <output.jsonl>
 * and requires this file first. It loads the real WordPress hook API, defines the few
 * environment functions that WordPress code expects from a full install, and provides the
 * typed value codec and the batch loop.
 */

declare(strict_types=1);

if (PHP_VERSION_ID < 80400 || PHP_VERSION_ID >= 80500) {
    fwrite(STDERR, 'wp-oracle needs PHP 8.4, found ' . PHP_VERSION . "\n");
    exit(3);
}
if (4 !== $argc) {
    fwrite(STDERR, "usage: php <driver> <wordpress-src-dir> <input.jsonl> <output.jsonl>\n");
    exit(2);
}

define('ABSPATH', rtrim($argv[1], '/') . '/');
define('WPINC', 'wp-includes');
define('QP_WP', ABSPATH . WPINC . '/');
define('QP_JSON_DEPTH', 100000);

$GLOBALS['qp_notices'] = [];

function __($text, $domain = 'default')
{
    return $text;
}

function _doing_it_wrong($function_name, $message, $version)
{
    $GLOBALS['qp_notices'][] = $function_name . ': ' . $message;
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
    // Same set as WordPress's default allowed protocols; data, kept for later drivers (kses, shortcodes).
    return ['http', 'https', 'ftp', 'ftps', 'mailto', 'news', 'irc', 'irc6', 'ircs', 'gopher', 'nntp', 'feed',
        'telnet', 'mms', 'rtsp', 'sms', 'svn', 'tel', 'fax', 'xmpp', 'webcal', 'urn'];
}

require QP_WP . 'plugin.php';
require __DIR__ . '/extract-functions.php';

/** Thrown by qp_tree() when a value nests deeper than any real one: a reference cycle. */
final class QpCycle extends RuntimeException
{
}

/** Encodes a PHP value as a typed tree that keeps int/string keys, -0, and raw bytes. */
function qp_tree(mixed $value, int $depth = 0): array
{
    if ($depth > 1100) {
        throw new QpCycle();
    }
    return match (true) {
        null === $value => ['t' => 'null'],
        is_bool($value) => ['t' => 'bool', 'v' => $value],
        is_int($value) => ['t' => 'int', 'v' => (string) $value],
        is_float($value) => ['t' => 'float', 'v' => qp_float($value)],
        is_string($value) => ['t' => 'string', 'b64' => base64_encode($value)],
        is_array($value) => ['t' => 'array', 'e' => qp_entries($value, $depth)],
        $value instanceof UnitEnum => ['t' => 'enum', 'c' => get_class($value), 'v' => $value->name],
        default => qp_object($value, $depth),
    };
}

/** The tree, or a cycle marker when the value refers to itself. */
function qp_safe_tree(mixed $value): array
{
    try {
        return qp_tree($value);
    } catch (QpCycle) {
        return ['t' => 'cycle'];
    }
}

/** Shortest round-trip text, as serialize() writes it. */
function qp_float(float $value): string
{
    return substr(serialize($value), 2, -1);
}

function qp_key(int|string $key): array
{
    return is_int($key)
        ? ['t' => 'int', 'v' => (string) $key]
        : ['t' => 'string', 'b64' => base64_encode($key)];
}

function qp_entries(array $array, int $depth): array
{
    $entries = [];
    foreach ($array as $key => $item) {
        $entries[] = [qp_key($key), qp_tree($item, $depth + 1)];
    }
    return $entries;
}

function qp_object(object $object, int $depth): array
{
    $properties = (array) $object;
    $class = get_class($object);
    if ($object instanceof __PHP_Incomplete_Class) {
        $class = $properties['__PHP_Incomplete_Class_Name'];
        unset($properties['__PHP_Incomplete_Class_Name']);
    }
    return ['t' => 'object', 'c' => $class, 'e' => qp_entries($properties, $depth)];
}

/** Builds a PHP value from a typed tree. Keys of type "append" mean $array[] = value. */
function qp_untree(array $node): mixed
{
    return match ($node['t']) {
        'null' => null,
        'bool' => $node['v'],
        'int' => (int) $node['v'],
        'float' => match ($node['v']) {
            'INF' => INF,
            '-INF' => -INF,
            'NAN' => NAN,
            default => (float) $node['v'],
        },
        'string' => base64_decode($node['b64'], true),
        'array' => qp_untree_array($node['e']),
        'object' => qp_untree_object($node),
        default => throw new InvalidArgumentException('cannot build a PHP value of type ' . $node['t']),
    };
}

function qp_untree_array(array $entries): array
{
    $array = [];
    foreach ($entries as [$key, $item]) {
        if ('append' === $key['t']) {
            $array[] = qp_untree($item);
        } else {
            $array[qp_untree($key)] = qp_untree($item);
        }
    }
    return $array;
}

function qp_untree_object(array $node): object
{
    if ('stdClass' !== $node['c']) {
        throw new InvalidArgumentException('only stdClass objects can be built, not ' . $node['c']);
    }
    $object = new stdClass();
    foreach ($node['e'] as [$key, $item]) {
        $object->{(string) qp_untree($key)} = qp_untree($item);
    }
    return $object;
}

/**
 * Reads one JSON case per input line ({"id":…,"in":…}), calls $handler with "in", and writes
 * {"id":…,"out":…} per line. A handler failure is written as {"id":…,"error":…}.
 */
function qp_run(callable $handler): void
{
    global $argv;
    $in = fopen($argv[2], 'rb');
    $out = fopen($argv[3], 'wb');
    if (false === $in || false === $out) {
        fwrite(STDERR, "cannot open the input or output file\n");
        exit(4);
    }
    $flags = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION
        | JSON_INVALID_UTF8_SUBSTITUTE | JSON_THROW_ON_ERROR;
    while (false !== ($line = fgets($in))) {
        if ('' === trim($line)) {
            continue;
        }
        $case = json_decode($line, true, QP_JSON_DEPTH, JSON_THROW_ON_ERROR);
        $GLOBALS['qp_notices'] = [];
        try {
            $row = ['id' => $case['id'], 'out' => $handler($case['in'])];
            if ([] !== $GLOBALS['qp_notices']) {
                $row['notices'] = $GLOBALS['qp_notices'];
            }
            $json = json_encode($row, $flags, QP_JSON_DEPTH);
        } catch (Throwable $e) {
            $json = json_encode(['id' => $case['id'], 'error' => get_class($e) . ': ' . $e->getMessage()], $flags);
        }
        fwrite($out, $json . "\n");
    }
    fclose($out);
}
