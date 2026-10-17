<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Writes src/test/resources/xss-corpus-expected.json: for every payload of xss-corpus.json what WordPress
 * returns for wp_kses(..., 'post') with the default filters ("wp"), for wp_kses(..., 'post') with none
 * ("bare") and for wp_kses(..., 'data') with the default filters ("data"). Regenerate after a WordPress bump
 * and review the diff:
 *   php src/test/php/xss-corpus.php <wordpress-src-dir> src/test/resources
 */

require __DIR__ . '/kses-cli.php';

if (3 !== $argc) {
    fwrite(STDERR, "usage: php xss-corpus.php <wordpress-src-dir> <resources-dir>\n");
    exit(2);
}
$dir = rtrim($argv[2], '/') . '/';
$payloads = json_decode(file_get_contents($dir . 'xss-corpus.json'), true, 512, JSON_THROW_ON_ERROR);

/** Characters that are hard to see in a diff are written as \u escapes. */
function qp_visible(string $json): string
{
    return preg_replace_callback(
        '/[\x{7F}-\x{A0}\x{AD}\x{2000}-\x{200F}\x{2028}-\x{202F}\x{FEFF}]/u',
        fn ($m) => sprintf('\\u%04x', unpack('N', iconv('UTF-8', 'UTF-32BE', $m[0]))[1]),
        $json
    );
}

$lines = [];
foreach ($payloads as $i => $text) {
    $row = [
        'id' => 'xss-' . ($i + 1),
        'text' => $text,
        'wp' => qp_kses_case(['fn' => 'kses', 'text' => $text, 'mode' => 'wp', 'context' => 'post']),
        'bare' => qp_kses_case(['fn' => 'kses', 'text' => $text, 'mode' => 'bare', 'context' => 'post']),
        'data' => qp_kses_case(['fn' => 'kses', 'text' => $text, 'mode' => 'wp', 'context' => 'data']),
    ];
    $lines[] = '  ' . qp_visible(json_encode($row, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR));
}
file_put_contents($dir . 'xss-corpus-expected.json', "[\n" . implode(",\n", $lines) . "\n]\n");
