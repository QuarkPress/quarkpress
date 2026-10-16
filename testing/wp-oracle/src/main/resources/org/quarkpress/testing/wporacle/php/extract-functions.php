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
