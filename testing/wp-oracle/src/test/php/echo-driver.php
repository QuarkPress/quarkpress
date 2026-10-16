<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Test driver for the oracle itself: builds each case's value in PHP, then returns its typed tree,
 * serialize() bytes, and whether the WordPress hook API is loaded. {"fail": msg} makes the case throw.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';

qp_run(function (array $in): array {
    if (isset($in['fail'])) {
        throw new RuntimeException($in['fail']);
    }
    $value = qp_untree($in['value']);
    return [
        'tree' => qp_tree($value),
        'serialize' => base64_encode(serialize($value)),
        'hooks' => function_exists('apply_filters') && function_exists('add_filter'),
    ];
});
