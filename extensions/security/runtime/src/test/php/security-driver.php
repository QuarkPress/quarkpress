<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Oracle driver for the security module.
 *   {"op":"sanitize_user","text":...}   sanitize_user(text, strict = true)
 *   {"op":"remove_accents","text":...}  remove_accents(text) with the site locale en_US
 *   {"op":"nicename","text":...}        the nicename wp_insert_user gives a new user registering with the login text,
 *                                     and whether wp_insert_user refuses the user (empty or over-60-character login, or empty nicename);
 *                                     the steps of wp_insert_user it needs are marked where the driver applies them
 *   {"op":"intl"}                       whether this PHP has the intl extension
 */

declare(strict_types=1);

require getenv('QP_ORACLE_HOME') . '/bootstrap.php';
qp_load_functions(QP_WP . 'functions.php', ['mbstring_binary_safe_encoding', 'reset_mbstring_encoding']);
qp_load_functions(QP_WP . 'formatting.php', [
    'sanitize_user', 'remove_accents', 'wp_strip_all_tags', 'sanitize_title', 'sanitize_title_with_dashes',
    'utf8_uri_encode',
]);

/** WordPress's default locale. */
function get_locale()
{
    return 'en_US';
}

// WordPress's default filters attach the dashes sanitizer to sanitize_title.
add_filter('sanitize_title', 'sanitize_title_with_dashes', 10, 3);

qp_run(function (array $in): array {
    switch ($in['op']) {
        case 'sanitize_user':
            return ['value' => sanitize_user($in['text'], true)];
        case 'remove_accents':
            return ['value' => remove_accents($in['text'])];
        case 'nicename':
            // Driver-applied rule 1 (wp_insert_user): the login is the strictly sanitized name, trimmed.
            $login = trim(sanitize_user($in['text'], true));
            // Driver-applied rule 2 (wp_insert_user): a new user's nicename starts from the first 50 characters of
            // the login; the login is ASCII here, so substr equals WordPress's mb_substr.
            $nicename = sanitize_title(substr($login, 0, 50));
            // Driver-applied rule 3 (wp_insert_user): an empty login, a login over 60 characters (user_login_too_long)
            // or an empty nicename is an error.
            return ['value' => $nicename, 'refused' => empty($login) || mb_strlen($login) > 60 || empty($nicename)];
        case 'intl':
            return ['value' => extension_loaded('intl') ? 'yes' : 'no'];
        default:
            throw new InvalidArgumentException('unknown op ' . $in['op']);
    }
});
