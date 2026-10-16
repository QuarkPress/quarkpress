<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-blocks. Each case ({"doc":…,"names":[…]}) runs WordPress's
 * parse_blocks, serialize_blocks, has_blocks and has_block (once per name) on the document.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
require QP_WP . 'class-wp-block-parser.php';
require QP_WP . 'blocks.php';

// blocks.php calls these two from functions.php and post.php; for valid UTF-8 input they reduce to this.
function wp_json_encode($value, $flags = 0, $depth = 512)
{
    return json_encode($value, $flags, $depth);
}

function get_post($post = null)
{
    return null;
}

/** JSON cannot carry INF or NAN, so they travel as {"$nonfinite": "INF" | "-INF" | "NAN"}. */
function qp_finite(mixed $value): mixed
{
    if (is_float($value) && !is_finite($value)) {
        return ['$nonfinite' => is_nan($value) ? 'NAN' : ($value > 0 ? 'INF' : '-INF')];
    }
    if (is_array($value)) {
        return array_map('qp_finite', $value);
    }
    return $value;
}

qp_run(function (array $in): array {
    $doc = $in['doc'];
    $blocks = parse_blocks($doc);
    $hasBlock = [];
    foreach ($in['names'] as $name) {
        $hasBlock[$name] = has_block($name, $doc);
    }
    return [
        'parsed' => qp_finite($blocks),
        'serialized' => serialize_blocks($blocks),
        'has_blocks' => has_blocks($doc),
        'has_block' => $hasBlock,
    ];
});
