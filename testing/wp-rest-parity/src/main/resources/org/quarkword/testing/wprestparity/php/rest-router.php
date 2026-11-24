<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Router of the REST parity site's php -S server. Harness rule, not WordPress behaviour: it does what Apache and
 * WordPress's rewrite rules do for pretty permalinks, and presents every request as one to localhost on port 80 (a
 * WordPress network cannot run on a port), so WordPress's hrefs read http://localhost/...
 */

$_SERVER['HTTP_HOST'] = 'localhost';
$_SERVER['SERVER_NAME'] = 'localhost';
$_SERVER['SERVER_PORT'] = '80';
if ('1' === ($_SERVER['HTTP_X_QP_PARITY_HTTPS'] ?? '')) {
    // Harness rule: php -S has no TLS; a request carrying X-QW-Parity-Https: 1 is presented as one over HTTPS through
    // the server variable WordPress reads to decide that.
    $_SERVER['HTTPS'] = 'on';
}

$qw_rest_root = rtrim($_SERVER['DOCUMENT_ROOT'], '/');
$qw_rest_path = parse_url('http://localhost' . $_SERVER['REQUEST_URI'], PHP_URL_PATH);
$qw_rest_file = is_string($qw_rest_path) ? realpath($qw_rest_root . rawurldecode($qw_rest_path)) : false;
if (false !== $qw_rest_file && is_dir($qw_rest_file) && str_ends_with($qw_rest_path, '/')
    && is_file($qw_rest_file . '/index.php')) {
    // A directory with an index.php (/wp-admin/) runs it, as Apache's DirectoryIndex does.
    $qw_rest_file = $qw_rest_file . '/index.php';
}
if (false !== $qw_rest_file && (!str_starts_with($qw_rest_file, $qw_rest_root . '/') || !is_file($qw_rest_file))) {
    $qw_rest_file = false;
}

if (false !== $qw_rest_file && !str_ends_with($qw_rest_file, '.php')) {
    // An existing file other than PHP: php -S serves it.
    unset($qw_rest_root, $qw_rest_path, $qw_rest_file);
    return false;
}

if (false !== $qw_rest_file) {
    // An existing PHP file runs as itself.
    $_SERVER['SCRIPT_NAME'] = substr($qw_rest_file, strlen($qw_rest_root));
} else {
    // Anything else goes to WordPress's front controller, as the rewrite rules send it.
    $qw_rest_file = $qw_rest_root . '/index.php';
    $_SERVER['SCRIPT_NAME'] = '/index.php';
}
$_SERVER['PHP_SELF'] = $_SERVER['SCRIPT_NAME'];
$_SERVER['SCRIPT_FILENAME'] = $qw_rest_file;
chdir(dirname($qw_rest_file));
$qw_rest_include = $qw_rest_file;
unset($qw_rest_root, $qw_rest_path, $qw_rest_file);
require $qw_rest_include;
