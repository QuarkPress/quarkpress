<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Writes the HTML5 named character reference table, as WordPress's HTML API holds it, in the format of
 * src/main/resources/org/quarkpress/lib/wpkses/html5-named-references.txt:
 *   php src/test/php/export-html5-references.php <wordpress-src-dir> > .../html5-named-references.txt
 * Needs the iconv extension.
 */

declare(strict_types=1);

if (2 !== $argc) {
    fwrite(STDERR, "usage: php export-html5-references.php <wordpress-src-dir>\n");
    exit(2);
}
$qp_dir = rtrim($argv[1], '/') . '/wp-includes/';
require $qp_dir . 'class-wp-token-map.php';
require $qp_dir . 'html-api/html5-named-character-references.php';

$qp_table = $html5_named_character_references->to_array();
ksort($qp_table, SORT_STRING);
echo "# HTML5 named character references (WHATWG table) as WordPress 7.1.3's HTML API holds them.\n";
echo "# <name, with ';' when the table has it> <code point in hex> [<second code point>]\n";
foreach ($qp_table as $qp_name => $qp_text) {
    $qp_points = unpack('N*', iconv('UTF-8', 'UTF-32BE', $qp_text));
    echo $qp_name, ' ', implode(' ', array_map(fn ($cp) => strtoupper(dechex($cp)), $qp_points)), "\n";
}
