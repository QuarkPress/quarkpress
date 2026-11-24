<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Functions of the REST parity harness's classic theme "quarkword" (harness rule, not WordPress behaviour): the
 * theme supports QuarkWord's synthetic theme declares by default, so the harness site and QuarkWord compare field
 * by field. WordPressRestSite copies the theme into wp-content/themes/quarkword/ and removes it when the site closes;
 * the must-use plugin's switch qw_parity_quarkword_theme selects it per site.
 */

add_action('after_setup_theme', static function (): void {
    add_theme_support('align-wide');
    add_theme_support('post-thumbnails');
    add_theme_support('responsive-embeds');
    add_theme_support('editor-styles');
    add_theme_support('html5', ['comment-form', 'comment-list', 'search-form', 'gallery', 'caption', 'style', 'script']);
});
