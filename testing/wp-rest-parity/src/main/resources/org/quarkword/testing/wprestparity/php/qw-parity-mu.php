<?php
/*
 * Copyright 2026 The QuarkWord Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Must-use plugin of the REST parity site. Harness rule, not WordPress behaviour: WordPressRestSite writes it into
 * wp-content/mu-plugins/ of the WordPress tree before php -S starts and removes it when the site closes (the qw-
 * prefix marks a leftover). It calls WordPress's public registration functions and filters only:
 *   - when the site option qw_parity_book_type is "1", registers the custom post type "book" (public, in REST under
 *     "books", labelled Books/Book, supporting title, editor, author, excerpt, thumbnail, revisions, custom-fields
 *     and autosave), the custom type both sides of the comparison hold, with the labels QuarkWord's test application
 *     gives it; a site without the switch sees WordPress's built-in types only;
 *   - when the site option qw_parity_no_trash is "1", makes every post type non-trashable through the
 *     rest_{type}_trashable filter, so WordPress's "trash not supported" answer can be recorded;
 *   - when the site option qw_parity_no_revisions is "1", keeps no revisions (wp_revisions_to_keep filter answers 0),
 *     the per-site stand-in for WP_POST_REVISIONS=0;
 *   - when the site option qw_parity_book_formats is "1", attaches the post_format taxonomy (not in REST) to "book",
 *     so a type whose taxonomies are all outside REST can be observed;
 *   - when "book" is registered, registers its post meta book_rating (integer, single, default 3) and book_tags
 *     (strings, several values), both in REST, at init priority 21 (after WordPress's footnotes at 20), the keys and
 *     the order QuarkWord's test application registers them in;
 *   - media switches (site options "1"): qw_parity_allow_svg adds svg => image/svg+xml through upload_mimes (at
 *     priority 100, after the network's upload_filetypes narrowing); qw_parity_no_unsupported_refusal makes
 *     wp_prevent_unsupported_mime_type_uploads answer false; qw_parity_hook_log appends "<hook>:<post id>" to the
 *     option qw_parity_hooks (a list) for the attachment and post hooks named in QW_PARITY_LOGGED_HOOKS;
 *     qw_parity_no_big_image_threshold makes big_image_size_threshold answer 0 (the image-processing reference's "filter threshold 0"); qw_parity_fail_on_second_size
 *     throws (a PHP fatal, as a memory or time limit would end the request) right after _wp_attachment_metadata
 *     is written the second time in a request, i.e. after the base save and the first sub-size's save; qw_parity_extra_sizes "900" registers
 *     the size qw-900w (900x0), "gravity" the sizes qw-left (100x100, left/top) and qw-right (100x100,
 *     right/bottom).
 *   - Imagick facts switches (site options "1"): qw_parity_webp_to_jpeg maps image/webp to image/jpeg in
 *     image_editor_output_format; qw_parity_quality_log appends to the option qw_parity_qualities (a list) every
 *     wp_editor_set_quality call and every file an image editor writes, with the editor's quality (see below);
 *     qw_parity_editor_gd makes wp_image_editors answer GD alone.
 *   - REST media switches (site options "1"): qw_parity_fatal_on_second_size raises a real PHP fatal error (memory
 *     exhausted, E_ERROR) at the same point as qw_parity_fail_on_second_size (the second write of
 *     _wp_attachment_metadata in a request, after the base save and the first sub-size's save), so WordPress's
 *     fatal-error handler answers the request; qw_parity_media_trashable makes
 *     rest_attachment_trashable answer true (the stand-in for MEDIA_TRASH); qw_parity_hidden_type registers the public
 *     custom type qw_hidden with show_in_rest false.
 *   - editor-extras switches (site options "1"): qw_parity_quarkword_theme answers "quarkword" (the harness's classic
 *     theme, php/qw-theme/) for the options stylesheet and template; qw_parity_no_core_patterns removes the theme
 *     support core-block-patterns at after_setup_theme (priority 100, after the theme's own setup);
 *     qw_parity_patterns registers the patterns qp/inner, qp/outer and qp/plain at init; qw_parity_block_types
 *     registers the block types acme/notice, acme/supports and the qw-probe/* types (one support or two each, titled
 *     "Probe <name>") at init (default priority) and acme/late at init priority 30; qw_parity_notice_block
 *     registers qw-it/notice, a dynamic block whose render callback prints the escaped message in
 *     <p class="qw-notice">; qw_parity_rest_name_meta registers the post meta qw_secret on post
 *     (single string, in REST under the name qw_public, an auth callback that refuses everyone).
 *   - admin-editor browser-facts switches (EditorBrowserFactsDbTest; site options of the main site, "1" unless said
 *     otherwise; to "omit" a handle is to replace its registration by a source-less one without dependencies, so the
 *     file is not printed while its dependents still are): qw_parity_no_concat defines CONCATENATE_SCRIPTS false when
 *     this plugin loads (never SCRIPT_DEBUG, which WordPress has defined by then); qw_parity_no_classic_editor
 *     dequeues and omits the script "editor" at enqueue_block_editor_assets (a dequeue alone leaves it printed:
 *     wp-block-library depends on it); qw_parity_bare_canvas removes
 *     __unstableResolvedAssets, styles and defaultEditorStyles through block_editor_settings_all;
 *     qw_parity_dataviews_media prints window.__experimentalDataViewsMediaModal = true before wp-editor and omits
 *     media-views; qw_parity_stub_media omits media-editor, media-audiovideo, mce-view, image-edit and imgareaselect,
 *     the styles media-views, imgareaselect and mediaelement, removes the media templates and replaces the data
 *     _wpMediaViewsL10n by {settings:{post:{id:0}}} and _wpPluploadSettings by {defaults:{}};
 *     qw_parity_stub_all_media also omits media-views, wp-plupload, the mediaelement scripts and postbox and prints
 *     wp.media = {view:{settings:{}}}; postboxes = {}; in the head; qw_parity_no_chrome_css omits the admin-chrome
 *     stylesheets (admin-menu, dashboard, list-tables, edit, revisions, media, themes, about, nav-menus, widgets,
 *     site-icon, l10n, wp-tooltip, admin-bar, wp-pointer, wp-auth-check); qw_parity_notes_404 / qw_parity_notes_400
 *     answer GET /wp/v2/comments with type=note by a 404 rest_no_route / 400 rest_invalid_param error
 *     (rest_pre_dispatch); qw_parity_themes_500 answers every /wp/v2/themes route by a 500 error ("empty": by an empty
 *     list) and drops those paths from the editor's preload; qw_parity_no_bindings_key removes
 *     __experimentalBlockBindingsSupportedAttributes from the editor settings; qw_parity_nonce_403_once answers the
 *     first PUT /wp/v2/posts/<id> after the switch was set by 403 rest_cookie_invalid_nonce "Cookie check failed"
 *     (the option qw_parity_nonce_403_done records that it happened; "always": every such PUT);
 *     qw_parity_block_styles adds the theme support wp-block-styles at after_setup_theme (priority 100).
 *   - loopback: when the environment variable QW_PARITY_LOOPBACK holds php -S's port (WordPressRestSite sets it for a
 *     server with two workers or more), a request WordPress makes to its own host (http://localhost/...) is sent to
 *     127.0.0.1 (the address php -S binds) on that port instead of port 80, where nothing listens; WP_ACCESSIBLE_HOSTS
 *     then names 127.0.0.1, so the configuration's block of external requests lets the re-sent request through.
 */

/**
 * Registers "book" when the current site's switch is set. Called at init for the served site, and by rest-setup.php
 * after switch_to_blog() for an operation that needs the type's registration (an auto-draft's title and link follow
 * the type's supports and registration, which the main site, where mu-plugins load, does not hold).
 */
function qw_parity_register_book(): void
{
    if ('1' === get_option('qw_parity_book_type')) {
        register_post_type('book', [
            'labels' => ['name' => 'Books', 'singular_name' => 'Book'],
            'public' => true,
            'show_in_rest' => true,
            'rest_base' => 'books',
            'supports' => ['title', 'editor', 'author', 'excerpt', 'thumbnail', 'revisions', 'custom-fields',
                'autosave'],
        ]);
        if ('1' === get_option('qw_parity_book_formats')) {
            register_taxonomy_for_object_type('post_format', 'book');
        }
    }
}

add_action('init', static function (): void {
    qw_parity_register_book();
    // The trash switch covers the types registered by now (WordPress's built-ins at init:0 and book above); a type
    // registered after init:10 would escape it.
    foreach (get_post_types() as $qw_type) {
        add_filter('rest_' . $qw_type . '_trashable', static function ($supports_trash) {
            return '1' === get_option('qw_parity_no_trash') ? false : $supports_trash;
        });
    }
}, 10);

add_filter('wp_revisions_to_keep', static function ($num) {
    return '1' === get_option('qw_parity_no_revisions') ? 0 : $num;
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
    if ('1' === get_option('qw_parity_allow_svg')) {
        $mimes['svg'] = 'image/svg+xml';
    }
    return $mimes;
}, 100);

add_filter('wp_prevent_unsupported_mime_type_uploads', static function ($prevent) {
    return '1' === get_option('qw_parity_no_unsupported_refusal') ? false : $prevent;
}, 10);

add_filter('big_image_size_threshold', static function ($threshold) {
    return '1' === get_option('qw_parity_no_big_image_threshold') ? 0 : $threshold;
}, 10);

const QW_PARITY_LOGGED_HOOKS = ['add_attachment', 'edit_attachment', 'attachment_updated', 'save_post',
    'transition_post_status', 'wp_insert_post', 'wp_after_insert_post', 'delete_attachment', 'delete_post',
    'before_delete_post', 'deleted_post'];

foreach (QW_PARITY_LOGGED_HOOKS as $qw_hook) {
    add_action($qw_hook, static function (...$args) use ($qw_hook): void {
        if ('1' !== get_option('qw_parity_hook_log')) {
            return;
        }
        // transition_post_status passes (new status, old status, post); the others pass the post (or its id) first.
        $subject = 'transition_post_status' === $qw_hook ? ($args[2] ?? null) : ($args[0] ?? null);
        $id = $subject instanceof WP_Post ? $subject->ID : (int) $subject;
        $log = get_option('qw_parity_hooks', []);
        $log = is_array($log) ? $log : [];
        $log[] = $qw_hook . ':' . $id;
        update_option('qw_parity_hooks', $log, false);
    }, 10, 4);
}
unset($qw_hook);

/**
 * Harness rule, not WordPress behaviour: ends the request with an engine fatal error (E_ERROR "Allowed memory size
 * exhausted"), which no catch block can intercept, as a real memory exhaustion during image generation does. The
 * limit is set a little above what is in use, so the shutdown handlers (WordPress's fatal-error handler) still run.
 */
function qw_parity_exhaust_memory(): void
{
    ini_set('memory_limit', (string) (memory_get_usage(true) + 16 * 1024 * 1024));
    $GLOBALS['qw_parity_ballast'] = str_repeat('x', 256 * 1024 * 1024);
}

// One counter for both "second size" switches: qw_parity_fail_on_second_size throws an uncaught \Error (as before);
// qw_parity_fatal_on_second_size exhausts memory instead, a real PHP fatal error.
$qw_parity_metadata_writes = 0;
foreach (['added_post_meta', 'updated_post_meta'] as $qw_meta_hook) {
    add_action($qw_meta_hook, static function ($meta_id, $object_id, $meta_key) use (&$qw_parity_metadata_writes): void {
        $fail = '1' === get_option('qw_parity_fail_on_second_size');
        $fatal = '1' === get_option('qw_parity_fatal_on_second_size');
        if ('_wp_attachment_metadata' !== $meta_key || (!$fail && !$fatal)) {
            return;
        }
        if (++$qw_parity_metadata_writes >= 2) {
            if ($fatal) {
                qw_parity_exhaust_memory();
            }
            throw new Error('qw_parity_fail_on_second_size: the request ends after the first sub-size');
        }
    }, 10, 3);
}
unset($qw_meta_hook);

/*
 * Harness rule, not WordPress behaviour (Imagick facts, MediaImagickFactsDbTest): qw_parity_webp_to_jpeg adds
 * image/webp => image/jpeg to the image_editor_output_format map, so the quality WordPress asks for a lossless WebP
 * written as JPEG can be recorded.
 */
add_filter('image_editor_output_format', static function ($formats) {
    if ('1' === get_option('qw_parity_webp_to_jpeg') && is_array($formats)) {
        $formats['image/webp'] = 'image/jpeg';
    }
    return $formats;
}, 1000);

/*
 * Harness rule, not WordPress behaviour: with qw_parity_editor_gd "1", wp_image_editors answers GD alone, so the same
 * upload can be recorded with GD on a server that has Imagick too.
 */
add_filter('wp_image_editors', static function ($editors) {
    return '1' === get_option('qw_parity_editor_gd') ? ['WP_Image_Editor_GD'] : $editors;
}, 1000);

/**
 * Harness rule: the first function on the call stack that is not this plugin's, a closure or the hook machinery
 * (apply_filters, WP_Hook): the WordPress function that asked for the filter, as "Class::method" or "function".
 */
function qw_parity_caller(): ?string
{
    foreach (debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS) as $frame) {
        $function = (string) ($frame['function'] ?? '');
        $class = (string) ($frame['class'] ?? '');
        if ('' === $function || str_starts_with($function, '{closure') || str_starts_with($function, 'qw_parity_')
            || 'apply_filters' === $function || 'WP_Hook' === $class) {
            continue;
        }
        return '' === $class ? $function : $class . '::' . $function;
    }
    return null;
}

/** Harness rule: the image editor that is running the current filter, found on the call stack; null when none. */
function qw_parity_running_editor(): ?WP_Image_Editor
{
    foreach (debug_backtrace(DEBUG_BACKTRACE_PROVIDE_OBJECT) as $frame) {
        if (isset($frame['object']) && $frame['object'] instanceof WP_Image_Editor) {
            return $frame['object'];
        }
    }
    return null;
}

/** Harness rule: appends one entry to the option qw_parity_qualities (a list) of the current site. */
function qw_parity_log_quality(array $entry): void
{
    $log = get_option('qw_parity_qualities', []);
    $log = is_array($log) ? $log : [];
    $log[] = $entry;
    update_option('qw_parity_qualities', $log, false);
}

/*
 * Harness rule, not WordPress behaviour: with qw_parity_quality_log "1", records every wp_editor_set_quality call
 * (the quality WordPress asks by default, the mime type, the size, the editor's class and the calling function) and, for every file an
 * editor writes (image_make_intermediate_size, which the editors' save applies), the file's name with the editor's
 * get_quality() at that moment. Both filters return their input unchanged.
 */
add_filter('wp_editor_set_quality', static function ($quality, $mime_type = null, $size = null) {
    if ('1' === get_option('qw_parity_quality_log')) {
        $editor = qw_parity_running_editor();
        qw_parity_log_quality([
            'filter' => 'wp_editor_set_quality',
            'quality' => $quality,
            'mime_type' => $mime_type,
            'size' => $size,
            'editor' => null === $editor ? null : get_class($editor),
            'caller' => qw_parity_caller(),
        ]);
    }
    return $quality;
}, 1000, 3);

add_filter('image_make_intermediate_size', static function ($filename) {
    if ('1' === get_option('qw_parity_quality_log')) {
        $editor = qw_parity_running_editor();
        qw_parity_log_quality([
            'filter' => 'image_make_intermediate_size',
            'file' => wp_basename((string) $filename),
            'quality' => null === $editor ? null : $editor->get_quality(),
            'editor' => null === $editor ? null : get_class($editor),
        ]);
    }
    return $filename;
}, 1000);

add_action('init', static function (): void {
    $sizes = get_option('qw_parity_extra_sizes');
    if ('900' === $sizes) {
        add_image_size('qw-900w', 900, 0);
    } elseif ('gravity' === $sizes) {
        add_image_size('qw-left', 100, 100, ['left', 'top']);
        add_image_size('qw-right', 100, 100, ['right', 'bottom']);
    }
}, 10);


add_filter('rest_attachment_trashable', static function ($supports_trash) {
    return '1' === get_option('qw_parity_media_trashable') ? true : $supports_trash;
}, 20);

/**
 * Registers "qw_hidden" (public, not in REST) when the current site's switch is set. Called at init for the served
 * site, and by rest-setup.php after switch_to_blog() before it inserts a post.
 */
function qw_parity_register_hidden(): void
{
    if ('1' === get_option('qw_parity_hidden_type') && !post_type_exists('qw_hidden')) {
        register_post_type('qw_hidden', [
            'labels' => ['name' => 'Hidden', 'singular_name' => 'Hidden'],
            'public' => true,
            'show_in_rest' => false,
        ]);
    }
}

add_action('init', 'qw_parity_register_hidden', 10);

// ---- editor-extras switches (EditorExtrasFactsDbTest) ---------------------------------------------------------------

foreach (['pre_option_stylesheet', 'pre_option_template'] as $qw_theme_option) {
    add_filter($qw_theme_option, static function ($value) {
        return '1' === get_option('qw_parity_quarkword_theme') ? 'quarkword' : $value;
    });
}
unset($qw_theme_option);

add_action('after_setup_theme', static function (): void {
    if ('1' === get_option('qw_parity_no_core_patterns')) {
        remove_theme_support('core-block-patterns');
    }
}, 100);

add_action('init', static function (): void {
    if ('1' !== get_option('qw_parity_patterns')) {
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
    if ('1' !== get_option('qw_parity_block_types')) {
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
    // probes of the attributes block supports add and where style goes (one support or two each)
    $probes = [
        'anchor' => ['anchor' => true],
        'border' => ['__experimentalBorder' => true],
        'layout' => ['__experimentalLayout' => true],
        'spacing' => ['customCSS' => false, 'spacing' => ['padding' => true]],
        'query' => ['align' => ['wide', 'full'], 'layout' => true, 'anchor' => true],
        'line-height' => ['typography' => ['lineHeight' => true], 'anchor' => true],
        'fit-text' => ['typography' => ['fitText' => true], 'anchor' => true],
        'text-indent' => ['typography' => ['textIndent' => true], 'anchor' => true],
        'color-true' => ['color' => true, 'anchor' => true],
        'link-only' => ['color' => ['text' => false, 'background' => false, 'link' => true]],
    ];
    foreach ($probes as $name => $supports) {
        register_block_type('qw-probe/' . $name, ['title' => 'Probe ' . $name, 'supports' => $supports]);
    }
});

add_action('init', static function (): void {
    if ('1' === get_option('qw_parity_block_types')) {
        register_block_type('acme/late', ['title' => 'Late']);
    }
}, 30);

add_action('init', static function (): void {
    if ('1' !== get_option('qw_parity_notice_block')) {
        return;
    }
    register_block_type('qw-it/notice', [
        'title' => 'Notice',
        'category' => 'text',
        'attributes' => ['message' => ['type' => 'string', 'default' => '']],
        'render_callback' => static function (array $attributes): string {
            return '<p class="qw-notice">' . esc_html((string) ($attributes['message'] ?? '')) . '</p>';
        },
    ]);
});

add_action('init', static function (): void {
    if ('1' !== get_option('qw_parity_rest_name_meta')) {
        return;
    }
    register_post_meta('post', 'qw_secret', [
        'type' => 'string',
        'single' => true,
        'show_in_rest' => ['name' => 'qw_public'],
        'auth_callback' => '__return_false',
    ]);
});

// ---- admin-editor browser-facts switches (EditorBrowserFactsDbTest) ------------------------------------------------

if ('1' === get_option('qw_parity_no_concat') && !defined('CONCATENATE_SCRIPTS')) {
    define('CONCATENATE_SCRIPTS', false);
}

/** Harness rule: the handle stays registered without a source and without dependencies, so nothing is printed for it. */
function qw_parity_omit_script(string $handle): void
{
    if (wp_script_is($handle, 'registered')) {
        $queued = wp_script_is($handle, 'enqueued');
        wp_deregister_script($handle);
        wp_register_script($handle, false, [], null);
        if ($queued) {
            wp_enqueue_script($handle);
        }
    }
}

/** Harness rule: as qw_parity_omit_script, for a stylesheet. */
function qw_parity_omit_style(string $handle): void
{
    if (wp_style_is($handle, 'registered')) {
        $queued = wp_style_is($handle, 'enqueued');
        wp_deregister_style($handle);
        wp_register_style($handle, false, [], null);
        if ($queued) {
            wp_enqueue_style($handle);
        }
    }
}

add_action('enqueue_block_editor_assets', static function (): void {
    if ('1' === get_option('qw_parity_no_classic_editor')) {
        // wp-block-library depends on "editor", so a dequeue alone would leave it printed.
        wp_dequeue_script('editor');
        qw_parity_omit_script('editor');
    }
    $stub_all = '1' === get_option('qw_parity_stub_all_media');
    if ('1' === get_option('qw_parity_dataviews_media')) {
        wp_add_inline_script('wp-editor', 'window.__experimentalDataViewsMediaModal = true;', 'before');
        qw_parity_omit_script('media-views');
    }
    if ('1' === get_option('qw_parity_stub_media') || $stub_all) {
        foreach (['media-editor', 'media-audiovideo', 'mce-view', 'image-edit', 'imgareaselect'] as $handle) {
            qw_parity_omit_script($handle);
        }
        foreach (['media-views', 'imgareaselect', 'mediaelement'] as $handle) {
            qw_parity_omit_style($handle);
        }
        foreach (['admin_footer', 'wp_footer', 'customize_controls_print_footer_scripts'] as $hook) {
            remove_action($hook, 'wp_print_media_templates');
        }
        $scripts = wp_scripts();
        if (wp_script_is('media-views', 'registered')) {
            $scripts->add_data('media-views', 'data', 'var _wpMediaViewsL10n = {"settings":{"post":{"id":0}}};');
        }
        if (wp_script_is('wp-plupload', 'registered')) {
            $scripts->add_data('wp-plupload', 'data', 'var _wpPluploadSettings = {"defaults":{}};');
        }
    }
    if ($stub_all) {
        foreach (['media-views', 'wp-plupload', 'mediaelement-core', 'mediaelement-migrate', 'mediaelement',
            'wp-mediaelement', 'postbox'] as $handle) {
            qw_parity_omit_script($handle);
        }
    }
    if ('1' === get_option('qw_parity_no_chrome_css')) {
        foreach (['admin-menu', 'dashboard', 'list-tables', 'edit', 'revisions', 'media', 'themes', 'about',
            'nav-menus', 'widgets', 'site-icon', 'l10n', 'wp-tooltip', 'admin-bar', 'wp-pointer', 'wp-auth-check']
            as $handle) {
            qw_parity_omit_style($handle);
        }
    }
}, 100);

add_action('admin_print_scripts', static function (): void {
    if ('1' === get_option('qw_parity_stub_all_media')) {
        echo "<script id=\"qw-parity-media-stub\">window.wp = window.wp || {}; wp.media = {view:{settings:{}}};"
            . " window.postboxes = {};</script>\n";
    }
}, 0);

add_filter('block_editor_settings_all', static function ($settings) {
    if ('1' === get_option('qw_parity_bare_canvas')) {
        unset($settings['__unstableResolvedAssets'], $settings['styles'], $settings['defaultEditorStyles']);
    }
    if ('1' === get_option('qw_parity_no_bindings_key')) {
        unset($settings['__experimentalBlockBindingsSupportedAttributes']);
    }
    return $settings;
}, PHP_INT_MAX);

add_filter('block_editor_rest_api_preload_paths', static function ($paths) {
    if (!in_array(get_option('qw_parity_themes_500'), ['1', 'empty'], true)) {
        return $paths;
    }
    $kept = [];
    foreach ($paths as $path) {
        $route = is_array($path) ? (string) ($path[0] ?? '') : (string) $path;
        if (!str_starts_with($route, '/wp/v2/themes')) {
            $kept[] = $path;
        }
    }
    return $kept;
}, PHP_INT_MAX);

add_filter('rest_pre_dispatch', static function ($result, $server, $request) {
    if (null !== $result) {
        return $result;
    }
    $route = $request->get_route();
    if ('/wp/v2/comments' === $route && 'GET' === $request->get_method() && 'note' === $request->get_param('type')) {
        if ('1' === get_option('qw_parity_notes_404')) {
            return new WP_Error('rest_no_route', 'No route was found matching the URL and request method.',
                ['status' => 404]);
        }
        if ('1' === get_option('qw_parity_notes_400')) {
            return new WP_Error('rest_invalid_param', 'Invalid parameter(s): type',
                ['status' => 400, 'params' => ['type' => 'type is not one of comment.']]);
        }
    }
    $themes = get_option('qw_parity_themes_500');
    if (str_starts_with($route, '/wp/v2/themes')) {
        if ('1' === $themes) {
            return new WP_Error('qw_parity_themes_500', 'Harness rule: the themes route fails.', ['status' => 500]);
        }
        if ('empty' === $themes) {
            return new WP_REST_Response([], 200);
        }
    }
    $nonce = get_option('qw_parity_nonce_403_once');
    if (in_array($nonce, ['1', 'always'], true) && 'PUT' === $request->get_method()
        && 1 === preg_match('#^/wp/v2/posts/\d+$#', $route)
        && ('always' === $nonce || '1' !== get_option('qw_parity_nonce_403_done'))) {
        update_option('qw_parity_nonce_403_done', '1');
        return new WP_Error('rest_cookie_invalid_nonce', 'Cookie check failed', ['status' => 403]);
    }
    return $result;
}, 10, 3);

add_action('after_setup_theme', static function (): void {
    if ('1' === get_option('qw_parity_block_styles')) {
        add_theme_support('wp-block-styles');
    }
}, 100);

/**
 * Harness rule, not WordPress behaviour: php -S listens on a free port, while WordPress addresses its own host as
 * http://localhost/ (port 80). When QW_PARITY_LOOPBACK holds the server's port, a request to http://localhost/...
 * that WordPress's own checks let through is sent to 127.0.0.1 (the address php -S binds, whatever localhost
 * resolves to first) on that port, with the same path, query and arguments,
 * as a web server serving the network on port 80 would receive it. Redirects the answer asks for are followed by the
 * HTTP library to port 80 and fail; the facts run's self-requests need none.
 */
$qw_parity_loopback = getenv('QW_PARITY_LOOPBACK');
if (false !== $qw_parity_loopback && '' !== $qw_parity_loopback && !defined('WP_ACCESSIBLE_HOSTS')) {
    // The site's configuration blocks external HTTP (WP_HTTP_BLOCK_EXTERNAL), which lets localhost through but not
    // 127.0.0.1; the re-sent request is the same self-request, so its address is made accessible too.
    define('WP_ACCESSIBLE_HOSTS', '127.0.0.1');
}
unset($qw_parity_loopback);

add_filter('pre_http_request', static function ($response, $args, $url) {
    $port = getenv('QW_PARITY_LOOPBACK');
    if (false !== $response || false === $port || '' === $port || !empty($args['qw_parity_loopback'])) {
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
    $args['qw_parity_loopback'] = true;
    return wp_remote_request($target, $args);
}, 10, 3);
