<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the media module's pure rules. WordPress's own functions are loaded from media.php and
 * functions.php; no filter is registered, so apply_filters hands back its value unchanged.
 *   {"op":"constrain","w0":…,"h0":…,"width":…,"height":…}
 *       wp_constrain_dimensions(w0, h0, width, height) → {"size":[w, h]}
 *   {"op":"resize","w0":…,"h0":…,"width":…,"height":…,"crop":false|true|[x, y]}
 *       image_resize_dimensions(w0, h0, width, height, crop) → {"region":[8 integers]} or {"region":false}
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', ['wp_fuzzy_number_match']);
qp_load_functions(QP_WP . 'media.php', ['image_resize_dimensions', 'wp_constrain_dimensions']);

qp_run(function (array $in): array {
    switch ($in['op']) {
        case 'constrain':
            return ['size' => wp_constrain_dimensions($in['w0'], $in['h0'], $in['width'], $in['height'])];
        case 'resize':
            return ['region' => image_resize_dimensions($in['w0'], $in['h0'], $in['width'], $in['height'], $in['crop'])];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
