<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for lib-wp-password. Passwords travel as hex so NUL bytes and invalid UTF-8 survive.
 *   {"op":"check","pw":hex,"hash":…}      wp_check_password of the password and of its addslashes() form,
 *                                         wp_password_needs_rehash (no filters, PHP's default bcrypt cost),
 *                                         WP_Application_Passwords::check_password, and the branch
 *                                         wp_check_password picks (restated from its tests, not a WordPress function)
 *   {"op":"hash","pw":hex,"cost":n}       wp_hash_password, cost set through the wp_hash_password_options filter
 *   {"op":"phpass","pw":hex,"setting":…}  PasswordHash::crypt_private; the result travels as hex
 *   {"op":"fast","pw":hex}                wp_fast_hash
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', ['wp_fast_hash', 'wp_verify_fast_hash']);
require QP_WP . 'pluggable.php';
require QP_WP . 'class-phpass.php';
require QP_WP . 'class-wp-application-passwords.php';

/** Which verifier wp_check_password uses for this stored hash. */
function qp_branch(string $hash): string
{
    return match (true) {
        strlen($hash) <= 32 => 'md5',
        str_starts_with($hash, '$wp') => 'wp',
        str_starts_with($hash, '$P$') => 'phpass',
        default => 'password_verify',
    };
}

qp_run(function (array $in): array {
    $password = hex2bin($in['pw']);
    switch ($in['op']) {
        case 'check':
            $hash = $in['hash'];
            $check = wp_check_password($password, $hash);
            $slashed = addslashes($password);
            return [
                'check' => $check,
                'check_slashed' => $slashed === $password ? $check : wp_check_password($slashed, $hash),
                'rehash' => wp_password_needs_rehash($hash),
                'app_check' => WP_Application_Passwords::check_password($password, $hash),
                'branch' => qp_branch($hash),
            ];
        case 'hash':
            $cost = (int) $in['cost'];
            $filter = static fn(array $options): array => ['cost' => $cost] + $options;
            add_filter('wp_hash_password_options', $filter);
            try {
                return ['hash' => wp_hash_password($password)];
            } finally {
                remove_filter('wp_hash_password_options', $filter);
            }
        case 'phpass':
            return ['hash' => bin2hex((new PasswordHash(8, true))->crypt_private($password, $in['setting']))];
        case 'fast':
            return ['hash' => wp_fast_hash($password)];
    }
    throw new InvalidArgumentException('unknown op ' . $in['op']);
});
