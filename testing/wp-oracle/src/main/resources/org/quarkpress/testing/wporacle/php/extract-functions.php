<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Loads selected top-level functions from a PHP file that cannot be included as a whole.
 * The file is tokenized, each requested function's source is cut out by brace matching,
 * and only those snippets are evaluated.
 */

function qp_is_open_brace(array|string $token): bool
{
    return '{' === $token
        || (is_array($token) && in_array($token[0], [T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES], true));
}

function qp_load_functions(string $file, array $names): void
{
    if (!function_exists('token_get_all')) {
        throw new RuntimeException('the tokenizer extension is required');
    }
    $source = file_get_contents($file);
    if (false === $source) {
        throw new RuntimeException('cannot read ' . $file);
    }
    $tokens = token_get_all($source);
    $count = count($tokens);
    $wanted = array_fill_keys($names, true);
    $loaded = [];
    $depth = 0;
    for ($i = 0; $i < $count; $i++) {
        $token = $tokens[$i];
        if (qp_is_open_brace($token)) {
            $depth++;
            continue;
        }
        if ('}' === $token) {
            $depth--;
            continue;
        }
        if (0 !== $depth || !is_array($token) || T_FUNCTION !== $token[0]) {
            continue;
        }
        $j = $i + 1;
        while ($j < $count && is_array($tokens[$j]) && T_WHITESPACE === $tokens[$j][0]) {
            $j++;
        }
        if ($j >= $count || !is_array($tokens[$j]) || T_STRING !== $tokens[$j][0] || !isset($wanted[$tokens[$j][1]])) {
            continue;
        }
        $code = '';
        $level = 0;
        for ($k = $i; $k < $count; $k++) {
            $piece = $tokens[$k];
            $code .= is_array($piece) ? $piece[1] : $piece;
            if (qp_is_open_brace($piece)) {
                $level++;
            } elseif ('}' === $piece && 0 === --$level) {
                break;
            }
        }
        eval($code);
        $loaded[$tokens[$j][1]] = true;
        $i = $k;
    }
    $missing = array_diff($names, array_keys($loaded));
    if ([] !== $missing) {
        throw new RuntimeException('not found in ' . $file . ': ' . implode(', ', $missing));
    }
}

/**
 * Cuts one run of statements out of a PHP file that cannot be included: the run starts at the statement holding the
 * token sequence $startMarker and ends at the first statement after it holding $endMarker. Markers are PHP source
 * snippets; whitespace and comments do not matter. Fails unless the start marker occurs exactly once and the end
 * marker follows it. Returns the run's source text, ready to be wrapped in a function and eval'ed.
 */
function qp_extract_statements(string $file, string $startMarker, string $endMarker): string
{
    $significant = static function (string $source): array {
        $out = [];
        foreach (token_get_all($source) as $token) {
            $id = is_array($token) ? $token[0] : null;
            if (T_WHITESPACE === $id || T_COMMENT === $id || T_DOC_COMMENT === $id || T_OPEN_TAG === $id) {
                continue;
            }
            $out[] = is_array($token) ? $token[1] : $token;
        }
        return $out;
    };
    $find = static function (array $haystack, array $needle, int $from) {
        $matches = [];
        $count = count($needle);
        for ($i = $from; $i + $count <= count($haystack); $i++) {
            if (array_slice($haystack, $i, $count) === $needle) {
                $matches[] = $i;
            }
        }
        return $matches;
    };
    $source = file_get_contents($file);
    if (false === $source) {
        throw new RuntimeException('cannot read ' . $file);
    }
    $tokens = [];
    foreach (token_get_all($source) as $token) {
        $tokens[] = $token;
    }
    // Index the significant tokens against the raw token list.
    $kept = [];
    foreach ($tokens as $index => $token) {
        $id = is_array($token) ? $token[0] : null;
        if (T_WHITESPACE !== $id && T_COMMENT !== $id && T_DOC_COMMENT !== $id && T_OPEN_TAG !== $id) {
            $kept[] = $index;
        }
    }
    $texts = array_map(static fn (int $i) => is_array($tokens[$i]) ? $tokens[$i][1] : $tokens[$i], $kept);
    $starts = $find($texts, $significant('<?php ' . $startMarker), 0);
    if (1 !== count($starts)) {
        throw new RuntimeException('expected exactly one start marker in ' . $file . ', found ' . count($starts));
    }
    $begin = $starts[0];
    while ($begin > 0 && !in_array($texts[$begin - 1], [';', '{', '}'], true)) {
        $begin--;
    }
    $ends = $find($texts, $significant('<?php ' . $endMarker), $starts[0]);
    if ([] === $ends) {
        throw new RuntimeException('end marker not found after the start marker in ' . $file);
    }
    $end = $ends[0];
    while (';' !== $texts[$end]) {
        $end++;
    }
    $code = '';
    for ($i = $kept[$begin]; $i <= $kept[$end]; $i++) {
        $code .= is_array($tokens[$i]) ? $tokens[$i][1] : $tokens[$i];
    }
    return $code;
}
