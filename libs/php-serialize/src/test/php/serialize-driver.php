<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-php-serialize. Read cases ({"kind":"read","b64":…}) run PHP's
 * unserialize()/serialize() and WordPress's maybe_serialize, maybe_unserialize, is_serialized and
 * is_serialized_string on the input bytes. Write cases ({"kind":"write","value":tree}) build a PHP
 * value from a typed tree and return serialize() and maybe_serialize() of it.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';

qp_load_functions(QP_WP . 'functions.php', ['maybe_serialize', 'maybe_unserialize', 'is_serialized', 'is_serialized_string']);

/** Lets E: cases name a real enum; unknown classes or cases make unserialize() fail, as in WordPress. */
enum Suit
{
    case Hearts;
    case Spades;
}

/** The text a database column receives for a maybe_serialize() result. */
function qp_db_text(mixed $value): string
{
    return match (true) {
        is_string($value) => $value,
        null === $value, false === $value => '',
        true === $value => '1',
        default => (string) $value,
    };
}

qp_run(function (array $in): array {
    if ('write' === $in['kind']) {
        $value = qp_untree($in['value']);
        return [
            'serialize' => base64_encode(serialize($value)),
            'maybe_serialize' => base64_encode(qp_db_text(maybe_serialize($value))),
        ];
    }
    $input = base64_decode($in['b64'], true);
    $value = @unserialize($input);
    $failed = false === $value && !str_starts_with($input, 'b:0;');
    $tree = $failed ? ['t' => 'failed'] : qp_safe_tree($value);
    $usable = !$failed && 'cycle' !== $tree['t'];
    $maybe = @maybe_unserialize($input);
    return [
        'unserialize' => $tree,
        'reserialize' => $usable ? base64_encode(serialize($value)) : null,
        'is_serialized' => is_serialized($input),
        'is_serialized_loose' => is_serialized($input, false),
        'is_serialized_string' => is_serialized_string($input),
        'maybe_unserialize' => qp_safe_tree($maybe),
        'maybe_serialize_input' => base64_encode(qp_db_text(maybe_serialize($input))),
        'maybe_serialize_value' => $usable ? base64_encode(qp_db_text(maybe_serialize($value))) : null,
    ];
});
