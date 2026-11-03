<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Must-use plugin of the REST parity site. Harness rule, not WordPress behaviour: WordPressRestSite writes it into
 * wp-content/mu-plugins/ of the WordPress tree before php -S starts and removes it when the site closes (the qp-
 * prefix marks a leftover). It calls WordPress's public registration functions and filters only:
 *   - when the site option qp_parity_book_type is "1", registers the custom post type "book" (public, in REST under
 *     "books", supporting title, editor, author, excerpt, thumbnail, revisions, custom-fields and autosave), the
 *     custom type both sides of the comparison hold; a site without the switch sees WordPress's built-in types only;
 *   - when the site option qp_parity_no_trash is "1", makes every post type non-trashable through the
 *     rest_{type}_trashable filter, so WordPress's "trash not supported" answer can be recorded;
 *   - when the site option qp_parity_no_revisions is "1", keeps no revisions (wp_revisions_to_keep filter answers 0),
 *     the per-site stand-in for WP_POST_REVISIONS=0;
 *   - when the site option qp_parity_book_formats is "1", attaches the post_format taxonomy (not in REST) to "book",
 *     so a type whose taxonomies are all outside REST can be observed.
 */

add_action('init', static function (): void {
    if ('1' === get_option('qp_parity_book_type')) {
        register_post_type('book', [
            'public' => true,
            'show_in_rest' => true,
            'rest_base' => 'books',
            'supports' => ['title', 'editor', 'author', 'excerpt', 'thumbnail', 'revisions', 'custom-fields',
                'autosave'],
        ]);
        if ('1' === get_option('qp_parity_book_formats')) {
            register_taxonomy_for_object_type('post_format', 'book');
        }
    }
    // The trash switch covers the types registered by now (WordPress's built-ins at init:0 and book above); a type
    // registered after init:10 would escape it.
    foreach (get_post_types() as $qp_type) {
        add_filter('rest_' . $qp_type . '_trashable', static function ($supports_trash) {
            return '1' === get_option('qp_parity_no_trash') ? false : $supports_trash;
        });
    }
}, 10);

add_filter('wp_revisions_to_keep', static function ($num) {
    return '1' === get_option('qp_parity_no_revisions') ? 0 : $num;
}, 10);
