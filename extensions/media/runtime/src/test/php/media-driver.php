<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the media module's pure rules. WordPress's own functions are loaded from media.php,
 * functions.php, formatting.php and compat.php; no filter is registered, so apply_filters hands back its value unchanged.
 *   {"op":"constrain","w0":…,"h0":…,"width":…,"height":…}
 *       wp_constrain_dimensions(w0, h0, width, height) → {"size":[w, h]}
 *   {"op":"resize","w0":…,"h0":…,"width":…,"height":…,"crop":false|true|[x, y]}
 *       image_resize_dimensions(w0, h0, width, height, crop) → {"region":[8 integers]} or {"region":false}
 *   {"op":"sanitize_file_name","name":…,"unfiltered_html":true|false,"locale":"en_US"|…}
 *       sanitize_file_name(name) → {"name": base64 of the result's bytes}
 *   {"op":"frac2dec","text":…}
 *       wp_exif_frac2dec(text) → {"type": gettype of the result, "text": the result as a string}
 *   {"op":"date2ts","text":…}
 *       wp_exif_date2ts(text) → {"ts": seconds or false}
 *   {"op":"round","value":…,"places":…}
 *       round(value, places) → {"bits": the result's IEEE 754 bits as 16 hex digits}
 * Harness rules, not WordPress behaviour: current_user_can answers only unfiltered_html, from the case; get_locale
 * returns the case's locale (en_US when absent); wp_is_valid_utf8 is the oracle bootstrap's; the default time zone
 * is UTC, as WordPress sets it when it loads.
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', [
    'wp_fuzzy_number_match', 'wp_check_filetype', 'wp_get_mime_types', 'get_allowed_mime_types',
    'mbstring_binary_safe_encoding', 'reset_mbstring_encoding',
]);
qp_load_functions(QP_WP . 'media.php', ['image_resize_dimensions', 'wp_constrain_dimensions']);
qp_load_functions(QP_WP . 'formatting.php', ['sanitize_file_name', 'remove_accents', 'seems_utf8']);
qp_load_functions(QP_WP . 'compat.php', ['_wp_can_use_pcre_u']);
qp_load_functions(ABSPATH . 'wp-admin/includes/image.php', ['wp_exif_frac2dec', 'wp_exif_date2ts']);

// Harness rule, not WordPress behaviour: WordPress sets the default time zone to UTC when it loads.
date_default_timezone_set('UTC');

$GLOBALS['qp_case'] = [];

/** Harness rule: the acting user's unfiltered_html capability comes from the case; nothing else is granted. */
function current_user_can($capability, ...$args)
{
    return 'unfiltered_html' === $capability && true === ($GLOBALS['qp_case']['unfiltered_html'] ?? false);
}

/** Harness rule: the site locale comes from the case. */
function get_locale()
{
    return $GLOBALS['qp_case']['locale'] ?? 'en_US';
}

qp_run(function (array $in): array {
    $GLOBALS['qp_case'] = $in;
    switch ($in['op']) {
        case 'constrain':
            return ['size' => wp_constrain_dimensions($in['w0'], $in['h0'], $in['width'], $in['height'])];
        case 'resize':
            return ['region' => image_resize_dimensions($in['w0'], $in['h0'], $in['width'], $in['height'], $in['crop'])];
        case 'sanitize_file_name':
            return ['name' => base64_encode(sanitize_file_name($in['name']))];
        case 'frac2dec':
            $value = wp_exif_frac2dec($in['text']);
            return ['type' => gettype($value), 'text' => (string) $value];
        case 'date2ts':
            return ['ts' => wp_exif_date2ts($in['text'])];
        case 'round':
            return ['bits' => bin2hex(pack('E', round((float) $in['value'], $in['places'])))];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
