<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Template of the REST parity site's configuration: what a wp-config.php defines for the scenario harness's
 * subdirectory network on localhost. WordPressRestSite fills in the database name and the WordPress directory and
 * writes the result into its run directory; the wp-config.php it writes next to the WordPress directory, and the
 * setup script, load it through QW_REST_CONFIG. The database server comes from QW_DB_HOST, QW_DB_PORT, QW_DB_USER and
 * QW_DB_PASSWORD.
 */

if (!defined('ABSPATH')) {
    define('ABSPATH', '{{ABSPATH}}');
}

function qw_rest_env(string $name): string
{
    $value = getenv($name);
    if (false === $value || '' === $value) {
        throw new RuntimeException("the environment variable $name is not set");
    }
    return $value;
}

define('DB_NAME', '{{DB_NAME}}');
define('DB_USER', qw_rest_env('QW_DB_USER'));
define('DB_PASSWORD', qw_rest_env('QW_DB_PASSWORD'));
define('DB_HOST', qw_rest_env('QW_DB_HOST') . ':' . qw_rest_env('QW_DB_PORT'));
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');
foreach (['AUTH_KEY', 'SECURE_AUTH_KEY', 'LOGGED_IN_KEY', 'NONCE_KEY', 'AUTH_SALT', 'SECURE_AUTH_SALT',
             'LOGGED_IN_SALT', 'NONCE_SALT'] as $qw_rest_key) {
    define($qw_rest_key, 'qw-scenario-' . strtolower($qw_rest_key));
}
unset($qw_rest_key);
define('WP_DEBUG', false);
define('DISABLE_WP_CRON', true);
define('WP_HTTP_BLOCK_EXTERNAL', true);
define('AUTOMATIC_UPDATER_DISABLED', true);
// No WP_RUN_CORE_TESTS (the scenario driver's one-process rule): every HTTP request is its own process here.
// Application passwords need HTTPS or a local environment; the server speaks plain HTTP on the loopback address.
define('WP_ENVIRONMENT_TYPE', 'local');
// The scenario harness's subdirectory network: main site http://localhost/.
define('MULTISITE', true);
define('SUBDOMAIN_INSTALL', false);
define('DOMAIN_CURRENT_SITE', 'localhost');
define('PATH_CURRENT_SITE', '/');
define('SITE_ID_CURRENT_SITE', 1);
define('BLOG_ID_CURRENT_SITE', 1);

$table_prefix = 'wp_';

require_once ABSPATH . 'wp-settings.php';
