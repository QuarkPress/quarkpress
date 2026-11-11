<?php
/*
 * Copyright 2026 The QuarkPress Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Must-use plugin of the REST parity site. Harness rule, not WordPress behaviour: WordPressRestSite writes it into
 * wp-content/mu-plugins/ of the WordPress tree before php -S starts and removes it when the site closes (the qp-
 * prefix marks a leftover). It calls WordPress's public registration functions and filters only:
 *   - when the site option qp_parity_book_type is "1", registers the custom post type "book" (public, in REST under
 *     "books", labelled Books/Book, supporting title, editor, author, excerpt, thumbnail, revisions, custom-fields
 *     and autosave), the custom type both sides of the comparison hold, with the labels QuarkPress's test application
 *     gives it; a site without the switch sees WordPress's built-in types only;
 *   - when the site option qp_parity_no_trash is "1", makes every post type non-trashable through the
 *     rest_{type}_trashable filter, so WordPress's "trash not supported" answer can be recorded;
 *   - when the site option qp_parity_no_revisions is "1", keeps no revisions (wp_revisions_to_keep filter answers 0),
 *     the per-site stand-in for WP_POST_REVISIONS=0;
 *   - when the site option qp_parity_book_formats is "1", attaches the post_format taxonomy (not in REST) to "book",
 *     so a type whose taxonomies are all outside REST can be observed;
 *   - when "book" is registered, registers its post meta book_rating (integer, single, default 3) and book_tags
 *     (strings, several values), both in REST, at init priority 21 (after WordPress's footnotes at 20), the keys and
 *     the order QuarkPress's test application registers them in;
 *   - media switches (site options "1"): qp_parity_allow_svg adds svg => image/svg+xml through upload_mimes (at
 *     priority 100, after the network's upload_filetypes narrowing); qp_parity_no_unsupported_refusal makes
 *     wp_prevent_unsupported_mime_type_uploads answer false; qp_parity_hook_log appends "<hook>:<post id>" to the
 *     option qp_parity_hooks (a list) for the attachment and post hooks named in QP_PARITY_LOGGED_HOOKS;
 *     qp_parity_no_big_image_threshold makes big_image_size_threshold answer 0 (the image-processing reference's "filter threshold 0"); qp_parity_fail_on_second_size
 *     throws (a PHP fatal, as a memory or time limit would end the request) right after _wp_attachment_metadata
 *     is written the second time in a request, i.e. after the base save and the first sub-size's save; qp_parity_extra_sizes "900" registers
 *     the size qp-900w (900x0), "gravity" the sizes qp-left (100x100, left/top) and qp-right (100x100,
 *     right/bottom).
 *   - REST media switches (site options "1"): qp_parity_fatal_on_second_size raises a real PHP fatal error (memory
 *     exhausted, E_ERROR) at the same point as qp_parity_fail_on_second_size (the second write of
 *     _wp_attachment_metadata in a request, after the base save and the first sub-size's save), so WordPress's
 *     fatal-error handler answers the request; qp_parity_media_trashable makes
 *     rest_attachment_trashable answer true (the stand-in for MEDIA_TRASH); qp_parity_hidden_type registers the public
 *     custom type qp_hidden with show_in_rest false.
 *   - editor-extras switches (site options "1"): qp_parity_quarkpress_theme answers "quarkpress" (the harness's classic
 *     theme, php/qp-theme/) for the options stylesheet and template; qp_parity_no_core_patterns removes the theme
 *     support core-block-patterns at after_setup_theme (priority 100, after the theme's own setup);
 *     qp_parity_patterns registers the patterns qp/inner, qp/outer and qp/plain at init; qp_parity_block_types
 *     registers the block types acme/notice and acme/supports at init (default priority) and acme/late at init
 *     priority 30; qp_parity_notice_block registers qp-it/notice, a dynamic block whose render callback prints the
 *     escaped message in <p class="qp-notice">; qp_parity_rest_name_meta registers the post meta qp_secret on post
 *     (single string, in REST under the name qp_public, an auth callback that refuses everyone).
 *   - loopback: when the environment variable QP_PARITY_LOOPBACK holds php -S's port (WordPressRestSite sets it for a
 *     server with two workers or more), a request WordPress makes to its own host (http://localhost/...) is sent to
 *     127.0.0.1 (the address php -S binds) on that port instead of port 80, where nothing listens; WP_ACCESSIBLE_HOSTS
 *     then names 127.0.0.1, so the configuration's block of external requests lets the re-sent request through.
 */

/**
 * Registers "book" when the current site's switch is set. Called at init for the served site, and by rest-setup.php
 * after switch_to_blog() for an operation that needs the type's registration (an auto-draft's title and link follow
 * the type's supports and registration, which the main site, where mu-plugins load, does not hold).
 */
function qp_parity_register_book(): void
{
    if ('1' === get_option('qp_parity_book_type')) {
        register_post_type('book', [
            'labels' => ['name' => 'Books', 'singular_name' => 'Book'],
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
}

add_action('init', static function (): void {
    qp_parity_register_book();
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

add_action('init', static function (): void {
    if (!post_type_exists('book')) {
        return;
    }
    register_post_meta('book', 'book_rating', [
        'type' => 'integer',
        'single' => true,
        'default' => 3,
        'show_in_rest' => true,
    ]);
    register_post_meta('book', 'book_tags', [
        'type' => 'string',
        'single' => false,
        'show_in_rest' => true,
    ]);
}, 21);

add_filter('upload_mimes', static function ($mimes) {
    if ('1' === get_option('qp_parity_allow_svg')) {
        $mimes['svg'] = 'image/svg+xml';
    }
    return $mimes;
}, 100);

add_filter('wp_prevent_unsupported_mime_type_uploads', static function ($prevent) {
    return '1' === get_option('qp_parity_no_unsupported_refusal') ? false : $prevent;
}, 10);

add_filter('big_image_size_threshold', static function ($threshold) {
    return '1' === get_option('qp_parity_no_big_image_threshold') ? 0 : $threshold;
}, 10);

const QP_PARITY_LOGGED_HOOKS = ['add_attachment', 'edit_attachment', 'attachment_updated', 'save_post',
    'transition_post_status', 'wp_insert_post', 'wp_after_insert_post', 'delete_attachment', 'delete_post',
    'before_delete_post', 'deleted_post'];

foreach (QP_PARITY_LOGGED_HOOKS as $qp_hook) {
    add_action($qp_hook, static function (...$args) use ($qp_hook): void {
        if ('1' !== get_option('qp_parity_hook_log')) {
            return;
        }
        // transition_post_status passes (new status, old status, post); the others pass the post (or its id) first.
        $subject = 'transition_post_status' === $qp_hook ? ($args[2] ?? null) : ($args[0] ?? null);
        $id = $subject instanceof WP_Post ? $subject->ID : (int) $subject;
        $log = get_option('qp_parity_hooks', []);
        $log = is_array($log) ? $log : [];
        $log[] = $qp_hook . ':' . $id;
        update_option('qp_parity_hooks', $log, false);
    }, 10, 4);
}
unset($qp_hook);

/**
 * Harness rule, not WordPress behaviour: ends the request with an engine fatal error (E_ERROR "Allowed memory size
 * exhausted"), which no catch block can intercept, as a real memory exhaustion during image generation does. The
 * limit is set a little above what is in use, so the shutdown handlers (WordPress's fatal-error handler) still run.
 */
function qp_parity_exhaust_memory(): void
{
    ini_set('memory_limit', (string) (memory_get_usage(true) + 16 * 1024 * 1024));
    $GLOBALS['qp_parity_ballast'] = str_repeat('x', 256 * 1024 * 1024);
}

// One counter for both "second size" switches: qp_parity_fail_on_second_size throws an uncaught \Error (as before);
// qp_parity_fatal_on_second_size exhausts memory instead, a real PHP fatal error.
$qp_parity_metadata_writes = 0;
foreach (['added_post_meta', 'updated_post_meta'] as $qp_meta_hook) {
    add_action($qp_meta_hook, static function ($meta_id, $object_id, $meta_key) use (&$qp_parity_metadata_writes): void {
        $fail = '1' === get_option('qp_parity_fail_on_second_size');
        $fatal = '1' === get_option('qp_parity_fatal_on_second_size');
        if ('_wp_attachment_metadata' !== $meta_key || (!$fail && !$fatal)) {
            return;
        }
        if (++$qp_parity_metadata_writes >= 2) {
            if ($fatal) {
                qp_parity_exhaust_memory();
            }
            throw new Error('qp_parity_fail_on_second_size: the request ends after the first sub-size');
        }
    }, 10, 3);
}
unset($qp_meta_hook);

add_action('init', static function (): void {
    $sizes = get_option('qp_parity_extra_sizes');
    if ('900' === $sizes) {
        add_image_size('qp-900w', 900, 0);
    } elseif ('gravity' === $sizes) {
        add_image_size('qp-left', 100, 100, ['left', 'top']);
        add_image_size('qp-right', 100, 100, ['right', 'bottom']);
    }
}, 10);


add_filter('rest_attachment_trashable', static function ($supports_trash) {
    return '1' === get_option('qp_parity_media_trashable') ? true : $supports_trash;
}, 20);

/**
 * Registers "qp_hidden" (public, not in REST) when the current site's switch is set. Called at init for the served
 * site, and by rest-setup.php after switch_to_blog() before it inserts a post.
 */
function qp_parity_register_hidden(): void
{
    if ('1' === get_option('qp_parity_hidden_type') && !post_type_exists('qp_hidden')) {
        register_post_type('qp_hidden', [
            'labels' => ['name' => 'Hidden', 'singular_name' => 'Hidden'],
            'public' => true,
            'show_in_rest' => false,
        ]);
    }
}

add_action('init', 'qp_parity_register_hidden', 10);

// ---- editor-extras switches (EditorExtrasFactsDbTest) ---------------------------------------------------------------

foreach (['pre_option_stylesheet', 'pre_option_template'] as $qp_theme_option) {
    add_filter($qp_theme_option, static function ($value) {
        return '1' === get_option('qp_parity_quarkpress_theme') ? 'quarkpress' : $value;
    });
}
unset($qp_theme_option);

add_action('after_setup_theme', static function (): void {
    if ('1' === get_option('qp_parity_no_core_patterns')) {
        remove_theme_support('core-block-patterns');
    }
}, 100);

add_action('init', static function (): void {
    if ('1' !== get_option('qp_parity_patterns')) {
        return;
    }
    register_block_pattern('qp/inner', [
        'title' => 'Inner',
        'content' => '<!-- wp:paragraph --><p>Inner</p><!-- /wp:paragraph -->',
        'categories' => ['buttons', 'columns', 'query', 'x'],
    ]);
    register_block_pattern('qp/outer', [
        'title' => 'Outer',
        'content' => '<!-- wp:group --><div class="wp-block-group">'
            . '<!-- wp:pattern {"slug":"qp/inner"} /-->'
            . '<!-- wp:pattern {"slug":"qp/missing"} /-->'
            . '<!-- wp:pattern {"slug":"qp/outer"} /-->'
            . '</div><!-- /wp:group -->',
    ]);
    register_block_pattern('qp/plain', [
        'title' => 'Plain',
        'content' => '<!-- wp:paragraph --><p>Plain</p><!-- /wp:paragraph -->',
        'inserter' => false,
        'viewportWidth' => 500,
        'description' => 'Plain text.',
        'keywords' => ['plain'],
        'blockTypes' => ['core/paragraph'],
        'postTypes' => ['post'],
    ]);
});

add_action('init', static function (): void {
    if ('1' !== get_option('qp_parity_block_types')) {
        return;
    }
    register_block_type('acme/notice', ['title' => 'Notice']);
    register_block_type('acme/supports', [
        'title' => 'Supports',
        'supports' => [
            'align' => true,
            'anchor' => true,
            'ariaLabel' => true,
            'color' => ['background' => true, 'text' => true, 'gradients' => true, 'link' => true],
            'typography' => ['fontSize' => true, 'lineHeight' => true],
            'border' => ['color' => true, 'radius' => true, 'style' => true, 'width' => true],
            'spacing' => ['margin' => true, 'padding' => true],
            'dimensions' => ['minHeight' => true],
            'shadow' => true,
            'background' => ['backgroundImage' => true],
            'layout' => true,
            'position' => ['sticky' => true],
            'filter' => ['duotone' => true],
        ],
    ]);
});

add_action('init', static function (): void {
    if ('1' === get_option('qp_parity_block_types')) {
        register_block_type('acme/late', ['title' => 'Late']);
    }
}, 30);

add_action('init', static function (): void {
    if ('1' !== get_option('qp_parity_notice_block')) {
        return;
    }
    register_block_type('qp-it/notice', [
        'title' => 'Notice',
        'category' => 'text',
        'attributes' => ['message' => ['type' => 'string', 'default' => '']],
        'render_callback' => static function (array $attributes): string {
            return '<p class="qp-notice">' . esc_html((string) ($attributes['message'] ?? '')) . '</p>';
        },
    ]);
});

add_action('init', static function (): void {
    if ('1' !== get_option('qp_parity_rest_name_meta')) {
        return;
    }
    register_post_meta('post', 'qp_secret', [
        'type' => 'string',
        'single' => true,
        'show_in_rest' => ['name' => 'qp_public'],
        'auth_callback' => '__return_false',
    ]);
});

/**
 * Harness rule, not WordPress behaviour: php -S listens on a free port, while WordPress addresses its own host as
 * http://localhost/ (port 80). When QP_PARITY_LOOPBACK holds the server's port, a request to http://localhost/...
 * that WordPress's own checks let through is sent to 127.0.0.1 (the address php -S binds, whatever localhost
 * resolves to first) on that port, with the same path, query and arguments,
 * as a web server serving the network on port 80 would receive it. Redirects the answer asks for are followed by the
 * HTTP library to port 80 and fail; the facts run's self-requests need none.
 */
$qp_parity_loopback = getenv('QP_PARITY_LOOPBACK');
if (false !== $qp_parity_loopback && '' !== $qp_parity_loopback && !defined('WP_ACCESSIBLE_HOSTS')) {
    // The site's configuration blocks external HTTP (WP_HTTP_BLOCK_EXTERNAL), which lets localhost through but not
    // 127.0.0.1; the re-sent request is the same self-request, so its address is made accessible too.
    define('WP_ACCESSIBLE_HOSTS', '127.0.0.1');
}
unset($qp_parity_loopback);

add_filter('pre_http_request', static function ($response, $args, $url) {
    $port = getenv('QP_PARITY_LOOPBACK');
    if (false !== $response || false === $port || '' === $port || !empty($args['qp_parity_loopback'])) {
        return $response;
    }
    $parts = wp_parse_url($url);
    if (!is_array($parts) || 'http' !== ($parts['scheme'] ?? '') || 'localhost' !== ($parts['host'] ?? '')
        || (isset($parts['port']) && 80 !== (int) $parts['port'])) {
        return $response;
    }
    if (!empty($args['reject_unsafe_urls']) && false === wp_http_validate_url($url)) {
        // WordPress refuses the URL itself; the request goes on unchanged so its own answer is recorded.
        return $response;
    }
    $target = 'http://127.0.0.1:' . (int) $port . ($parts['path'] ?? '/')
        . (isset($parts['query']) ? '?' . $parts['query'] : '');
    $args['reject_unsafe_urls'] = false;
    $args['qp_parity_loopback'] = true;
    return wp_remote_request($target, $args);
}, 10, 3);
