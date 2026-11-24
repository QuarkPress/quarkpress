<?php
/*
 * Copyright 2026 The QuarkWord Authors
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
 *                                                          environment QW_NETWORK_DOMAIN / QW_NETWORK_PATH
 *   {"op":"normalize","domain":…,"path":…}                  wp_normalize_site_data of a domain and a path
 *   {"op":"reserved"}                                       get_subdirectory_reserved_names
 */

declare(strict_types=1);

require getenv('QW_ORACLE_HOME') . '/bootstrap.php';
qw_load_functions(QW_WP . 'formatting.php', ['trailingslashit', 'untrailingslashit']);
qw_load_functions(QW_WP . 'ms-load.php', ['get_site_by_path', 'ms_load_current_site_and_network']);
if (false !== getenv('QW_NETWORK_DOMAIN')) {
    define('DOMAIN_CURRENT_SITE', getenv('QW_NETWORK_DOMAIN'));
    define('PATH_CURRENT_SITE', getenv('QW_NETWORK_PATH'));
}

function is_admin(): bool
{
    return $GLOBALS['qw_is_admin'];
}

/** Thrown by the segments op to stop get_site_by_path before it reads the database. */
final class QwSegmentsCaptured extends RuntimeException
{
}

eval('function qw_ms_lookup_path($uri) { $_SERVER[\'REQUEST_URI\'] = $uri; '
    . qw_extract_statements(QW_WP . 'ms-settings.php', "\$_SERVER['REQUEST_URI']", 'explode(')
    . ' return $path; }');
qw_load_functions(QW_WP . 'ms-site.php', ['wp_normalize_site_data']);
qw_load_functions(QW_WP . 'ms-functions.php', ['get_subdirectory_reserved_names']);

qw_run(function (array $in): array {
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
            $GLOBALS['qw_is_admin'] = $in['admin'];
            return ['path' => qw_ms_lookup_path($in['path'])];
        case 'segments':
            $captured = null;
            $reached = false;
            $capture = static function ($pre, $domain, $path, $segments) use (&$captured, &$reached) {
                $captured = $segments;
                $reached = true;
                throw new QwSegmentsCaptured();
            };
            add_filter('pre_get_site_by_path', $capture, 10, 4);
            try {
                ms_load_current_site_and_network($in['host'], $in['path'], false);
            } catch (QwSegmentsCaptured) {
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
