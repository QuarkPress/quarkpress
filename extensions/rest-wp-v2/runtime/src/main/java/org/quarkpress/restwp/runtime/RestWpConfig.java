package org.quarkpress.restwp.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** REST API settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.rest-wp-v2")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface RestWpConfig {

    /** The base path of the REST API under each site's path, as WordPress's {@code rest_get_url_prefix} is. */
    @WithDefault("/wp-json")
    String path();

    /** Cross-origin requests. */
    Cors cors();

    /**
     * Whether front-end requests of a site's root ({@code ?p=}, {@code ?page_id=}, {@code ?attachment_id=},
     * {@code ?post_type=...&p=}, {@code ?preview=}) get the minimal page (the site's name, the post's title, date and
     * content) until themes exist; when off, no front-end request is answered by this module.
     */
    @WithDefault("true")
    boolean fallbackFrontEnd();

    /** The features the synthetic theme {@code quarkpress} declares to the block editor. */
    Theme theme();

    /** Which other origins may call the REST API from a browser. */
    interface Cors {

        /**
         * The origins answered with {@code Access-Control-Allow-Origin}; none by default, so no response carries that
         * header.
         */
        Optional<List<String>> origins();
    }

    /**
     * The theme features (WordPress's {@code add_theme_support}) the synthetic theme declares, as the themes routes
     * answer them in {@code theme_supports}. A boolean feature left false is "not declared"; the features whose value
     * is an object or a list (custom background, header and logo, the editor's colour, font size, gradient and spacing
     * presets, block templates and template parts) are never declared.
     */
    interface Theme {

        /** Wide and full alignments ({@code align-wide}). */
        @WithDefault("true")
        boolean alignWide();

        /** Feed links in the page head ({@code automatic-feed-links}). */
        @WithDefault("false")
        boolean automaticFeedLinks();

        /** Selective refresh of widgets in the customizer ({@code customize-selective-refresh-widgets}). */
        @WithDefault("false")
        boolean customizeSelectiveRefreshWidgets();

        /** The dark editor style ({@code dark-editor-style}). */
        @WithDefault("false")
        boolean darkEditorStyle();

        /** No custom colours in the editor ({@code disable-custom-colors}). */
        @WithDefault("false")
        boolean disableCustomColors();

        /** No custom font sizes in the editor ({@code disable-custom-font-sizes}). */
        @WithDefault("false")
        boolean disableCustomFontSizes();

        /** No custom gradients in the editor ({@code disable-custom-gradients}). */
        @WithDefault("false")
        boolean disableCustomGradients();

        /** No generated layout styles ({@code disable-layout-styles}). */
        @WithDefault("false")
        boolean disableLayoutStyles();

        /** The editor styles wrapper ({@code editor-styles}). */
        @WithDefault("true")
        boolean editorStyles();

        /** Featured images for every post type ({@code post-thumbnails}). */
        @WithDefault("true")
        boolean postThumbnails();

        /** Responsive embedded content ({@code responsive-embeds}). */
        @WithDefault("true")
        boolean responsiveEmbeds();

        /** The document title tag ({@code title-tag}). */
        @WithDefault("false")
        boolean titleTag();

        /** WordPress's default block styles on the front end ({@code wp-block-styles}). */
        @WithDefault("false")
        boolean wpBlockStyles();

        /**
         * The HTML5 markup the theme opts into ({@code html5}): any of {@code search-form}, {@code comment-form},
         * {@code comment-list}, {@code gallery}, {@code caption}, {@code script}, {@code style}; the single value
         * {@code none} declares nothing (the feature answers false), and may not be given beside another value.
         */
        @WithDefault("comment-form,comment-list,search-form,gallery,caption,style,script")
        List<String> html5();

        /**
         * The post formats beyond {@code standard} the theme supports ({@code post-formats}, answered as
         * {@code formats}): any of {@code aside}, {@code chat}, {@code gallery}, {@code link}, {@code image},
         * {@code quote}, {@code status}, {@code video}, {@code audio}, in the order given; none by default.
         */
        Optional<List<String>> formats();
    }
}
