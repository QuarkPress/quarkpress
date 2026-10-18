<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the multisite module.
 *   {"op":"paths","domain":…,"path":…,"segments":n|null}  the candidate paths get_site_by_path would query,
 *                                                          captured through pre_get_site_by_path (no database)
 *   {"op":"lookup","path":…,"admin":bool}                   the request path as the multisite bootstrap prepares
 *                                                          it for the lookup: the statements of ms-settings.php,
 *                                                          extracted from the pinned file, run with is_admin() = admin
 *   {"op":"segments","host":…,"path":…}                     the segment count ms_load_current_site_and_network
 *                                                          passes to get_site_by_path for the network named by the
 *                                                          environment QP_NETWORK_DOMAIN / QP_NETWORK_PATH
 *   {"op":"normalize","domain":…,"path":…}                  wp_normalize_site_data of a domain and a path
 *   {"op":"reserved"}                                       get_subdirectory_reserved_names
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'formatting.php', ['trailingslashit', 'untrailingslashit']);
qp_load_functions(QP_WP . 'ms-load.php', ['get_site_by_path', 'ms_load_current_site_and_network']);
if (false !== getenv('QP_NETWORK_DOMAIN')) {
    define('DOMAIN_CURRENT_SITE', getenv('QP_NETWORK_DOMAIN'));
    define('PATH_CURRENT_SITE', getenv('QP_NETWORK_PATH'));
}

function is_admin(): bool
{
    return $GLOBALS['qp_is_admin'];
}

/** Thrown by the segments op to stop get_site_by_path before it reads the database. */
final class QpSegmentsCaptured extends RuntimeException
{
}

eval('function qp_ms_lookup_path($uri) { $_SERVER[\'REQUEST_URI\'] = $uri; '
    . qp_extract_statements(QP_WP . 'ms-settings.php', "\$_SERVER['REQUEST_URI']", 'explode(')
    . ' return $path; }');
qp_load_functions(QP_WP . 'ms-site.php', ['wp_normalize_site_data']);
qp_load_functions(QP_WP . 'ms-functions.php', ['get_subdirectory_reserved_names']);

qp_run(function (array $in): array {
    switch ($in['op']) {
        case 'paths':
            $captured = null;
            $capture = static function ($pre, $domain, $path, $segments, $paths) use (&$captured) {
                $captured = $paths;
                return false;
            };
            add_filter('pre_get_site_by_path', $capture, 10, 5);
            try {
                get_site_by_path($in['domain'], $in['path'], $in['segments']);
            } finally {
                remove_filter('pre_get_site_by_path', $capture, 10);
            }
            return ['paths' => $captured];
        case 'lookup':
            $GLOBALS['qp_is_admin'] = $in['admin'];
            return ['path' => qp_ms_lookup_path($in['path'])];
        case 'segments':
            $captured = null;
            $reached = false;
            $capture = static function ($pre, $domain, $path, $segments) use (&$captured, &$reached) {
                $captured = $segments;
                $reached = true;
                throw new QpSegmentsCaptured();
            };
            add_filter('pre_get_site_by_path', $capture, 10, 4);
            try {
                ms_load_current_site_and_network($in['host'], $in['path'], false);
            } catch (QpSegmentsCaptured) {
            } finally {
                remove_filter('pre_get_site_by_path', $capture, 10);
            }
            return ['segments' => $captured, 'captured' => $reached];
        case 'normalize':
            $data = wp_normalize_site_data(['domain' => $in['domain'], 'path' => $in['path']]);
            return ['domain' => $data['domain'], 'path' => $data['path']];
        case 'reserved':
            return ['names' => get_subdirectory_reserved_names()];
    }
    throw new InvalidArgumentException('unknown op ' . $in['op']);
});
