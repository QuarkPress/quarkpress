<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Router of the REST parity site's php -S server. Harness rule, not WordPress behaviour: it does what Apache and
 * WordPress's rewrite rules do for pretty permalinks, and presents every request as one to localhost on port 80 (a
 * WordPress network cannot run on a port), so WordPress's hrefs read http://localhost/...
 */

$_SERVER['HTTP_HOST'] = 'localhost';
$_SERVER['SERVER_NAME'] = 'localhost';
$_SERVER['SERVER_PORT'] = '80';

$qp_rest_root = rtrim($_SERVER['DOCUMENT_ROOT'], '/');
$qp_rest_path = parse_url('http://localhost' . $_SERVER['REQUEST_URI'], PHP_URL_PATH);
$qp_rest_file = is_string($qp_rest_path) ? realpath($qp_rest_root . rawurldecode($qp_rest_path)) : false;
if (false !== $qp_rest_file && is_dir($qp_rest_file) && str_ends_with($qp_rest_path, '/')
    && is_file($qp_rest_file . '/index.php')) {
    // A directory with an index.php (/wp-admin/) runs it, as Apache's DirectoryIndex does.
    $qp_rest_file = $qp_rest_file . '/index.php';
}
if (false !== $qp_rest_file && (!str_starts_with($qp_rest_file, $qp_rest_root . '/') || !is_file($qp_rest_file))) {
    $qp_rest_file = false;
}

if (false !== $qp_rest_file && !str_ends_with($qp_rest_file, '.php')) {
    // An existing file other than PHP: php -S serves it.
    unset($qp_rest_root, $qp_rest_path, $qp_rest_file);
    return false;
}

if (false !== $qp_rest_file) {
    // An existing PHP file runs as itself.
    $_SERVER['SCRIPT_NAME'] = substr($qp_rest_file, strlen($qp_rest_root));
} else {
    // Anything else goes to WordPress's front controller, as the rewrite rules send it.
    $qp_rest_file = $qp_rest_root . '/index.php';
    $_SERVER['SCRIPT_NAME'] = '/index.php';
}
$_SERVER['PHP_SELF'] = $_SERVER['SCRIPT_NAME'];
$_SERVER['SCRIPT_FILENAME'] = $qp_rest_file;
chdir(dirname($qp_rest_file));
$qp_rest_include = $qp_rest_file;
unset($qp_rest_root, $qp_rest_path, $qp_rest_file);
require $qp_rest_include;
