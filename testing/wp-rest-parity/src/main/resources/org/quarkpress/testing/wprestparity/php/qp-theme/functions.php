<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Functions of the REST parity harness's classic theme "quarkpress" (harness rule, not WordPress behaviour): the
 * theme supports QuarkPress's synthetic theme declares by default, so the harness site and QuarkPress compare field
 * by field. WordPressRestSite copies the theme into wp-content/themes/quarkpress/ and removes it when the site closes;
 * the must-use plugin's switch qp_parity_quarkpress_theme selects it per site.
 */

add_action('after_setup_theme', static function (): void {
    add_theme_support('align-wide');
    add_theme_support('post-thumbnails');
    add_theme_support('responsive-embeds');
    add_theme_support('editor-styles');
    add_theme_support('html5', ['comment-form', 'comment-list', 'search-form', 'gallery', 'caption', 'style', 'script']);
});
