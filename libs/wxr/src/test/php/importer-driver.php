<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wxr. Runs the WordPress Importer 0.9.6 (its checkout is in QW_WXR_IMPORTER):
 *   {"kind":"parse","file":path} -> {"available":…,"parser":…,"result":{…}} or {…,"error":code,"message":…}
 *     "available": the parser the importer's auto-selection tries first on this PHP (simplexml, xml or
 *     xmlprocessor); "parser": the parser whose result is returned (xmlprocessor after a fallback).
 *   {"kind":"meta","b64":…} -> {"tree":…}: the importer's maybe_unserialize (object headers rewritten to
 *     stdClass, then core maybe_unserialize) on the value.
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';

$importer = getenv('QW_WXR_IMPORTER');
if (!is_string($importer) || '' === $importer) {
    fwrite(STDERR, "QW_WXR_IMPORTER is not set\n");
    exit(5);
}
define('QW_IMPORTER', rtrim($importer, '/') . '/');

/** The part of WordPress's error object the importer uses. */
class WP_Error
{
    public function __construct(public $code = '', public $message = '', public $data = '')
    {
    }

    public function get_error_code()
    {
        return $this->code;
    }

    public function get_error_message()
    {
        return $this->message;
    }

    public function get_error_data()
    {
        return $this->data;
    }
}

function is_wp_error($thing): bool
{
    return $thing instanceof WP_Error;
}

function esc_html($text)
{
    return htmlspecialchars((string) $text, ENT_QUOTES);
}

/** The importer's class extends this; nothing of it is needed to unserialize meta. */
class WP_Importer
{
}

require QW_WP . 'html-api/class-wp-html-span.php';
require QW_IMPORTER . 'src/php-toolkit/load.php';
foreach (['class-wxr-parser', 'class-wxr-parser-simplexml', 'class-wxr-parser-xml', 'class-wxr-parser-regex',
    'class-wxr-parser-xml-processor'] as $file) {
    require QW_IMPORTER . 'src/parsers/' . $file . '.php';
}
qw_load_functions(QW_WP . 'functions.php', ['is_serialized', 'maybe_unserialize']);
require QW_IMPORTER . 'src/class-wp-import.php';

/** Makes the importer's protected meta helper callable. */
final class QwImport extends WP_Import
{
    public function meta(string $value): mixed
    {
        return $this->maybe_unserialize($value);
    }
}

function qw_available_parser(): string
{
    if (extension_loaded('simplexml') && extension_loaded('dom')) {
        return 'simplexml';
    }
    return extension_loaded('xml') ? 'xml' : 'xmlprocessor';
}

qw_run(function (array $in): array {
    if ('meta' === $in['kind']) {
        return ['tree' => qw_safe_tree(@(new QwImport())->meta(base64_decode($in['b64'], true)))];
    }
    $file = $in['file'];
    $available = qw_available_parser();
    $used = $available;
    if ('simplexml' === $available) {
        $probe = (new WXR_Parser_SimpleXML())->parse($file);
        if (is_wp_error($probe) && 'SimpleXML_parse_error' === $probe->get_error_code()) {
            $used = 'xmlprocessor';
        }
    } elseif ('xml' === $available) {
        $probe = (new WXR_Parser_XML())->parse($file);
        if (is_wp_error($probe) && 'XML_parse_error' === $probe->get_error_code()) {
            $used = 'xmlprocessor';
        }
    }
    $result = (new WXR_Parser())->parse($file);
    if (is_wp_error($result)) {
        return ['available' => $available, 'parser' => $used, 'error' => $result->get_error_code(),
            'message' => $result->get_error_message()];
    }
    return ['available' => $available, 'parser' => $used, 'result' => $result];
});
