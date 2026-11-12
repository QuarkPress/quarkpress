package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigurationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.RestWpConfig;
import org.quarkpress.security.Capabilities;

/**
 * The theme QuarkPress tells the block editor about: a classic theme named {@code quarkpress} that exists only as the
 * record the themes routes serve, since no theme is installed. Its header fields are what WordPress answers for a
 * theme whose style sheet declares a name and a description only (the author rendered "Anonymous", the other fields
 * empty); its features are the configured ones ({@code quarkpress.rest-wp-v2.theme.*}), answered under the 24 feature
 * keys WordPress shows, in WordPress's order, each undeclared feature at its default (false, or {@code ["standard"]}
 * for the post formats); its directory URL is the current site's; the default template types and template part
 * areas are WordPress's own lists, which do not depend on the theme. It links its user global styles and the theme
 * export to nobody: there are no global styles routes, and only a block theme exports.
 *
 * <p>Who may view it is WordPress's rule: a caller who switches themes (or manages the network's) sees every theme,
 * and a caller who may edit posts of some type sees the active one.
 */
@ApplicationScoped
public class SyntheticTheme {

    /** The theme's directory name, its {@code stylesheet} and {@code template}. */
    public static final String STYLESHEET = "quarkpress";

    /** The file holding WordPress's default template types and template part areas. */
    static final String DEFAULTS = "/org/quarkpress/restwp/theme-template-defaults.json";

    private static final String NAME = "QuarkPress";

    private static final String DESCRIPTION = "The synthetic theme QuarkPress serves to the block editor.";

    /** The values of {@code html5} WordPress knows. */
    static final List<String> HTML5 =
            List.of("search-form", "comment-form", "comment-list", "gallery", "caption", "script", "style");

    /** The post formats beyond {@code standard}, in WordPress's order. */
    static final List<String> FORMATS =
            List.of("aside", "chat", "gallery", "link", "image", "quote", "status", "video", "audio");

    private static final String HTML5_KEY = "quarkpress.rest-wp-v2.theme.html5";

    private static final String FORMATS_KEY = "quarkpress.rest-wp-v2.theme.formats";

    @Inject
    RestWpConfig config;

    @Inject
    Capabilities capabilities;

    @Inject
    PostTypes types;

    @Inject
    PostPermissions permissions;

    /** The 24 feature keys in WordPress's order with the configured values, built when the application starts. */
    private ObjectNode supports;

    /** WordPress's default template types and template part areas, read from the class path when it starts. */
    private ObjectNode defaults;

    /** The configured features are checked when the application starts, so a mistake fails the start. */
    void start(@Observes StartupEvent started) {
        supports = build(config.theme());
        defaults = readDefaults();
    }

    /** Whether the caller may see every theme: {@code switch_themes} or {@code manage_network_themes}. */
    public boolean managesThemes(RestRequest request) {
        return capabilities.can(request.identity(), "switch_themes", Optional.empty())
                || capabilities.can(request.identity(), "manage_network_themes", Optional.empty());
    }

    /** Whether the caller may see the active theme: a theme manager, or anyone who may edit posts of some type. */
    public boolean mayViewActive(RestRequest request) {
        return managesThemes(request) || EditorAccess.canEditAnyType(request, types, permissions);
    }

    /** The theme's record without its links, in WordPress's field order, for the site {@code urls} serves. */
    public ObjectNode record(RestUrls urls) {
        ObjectNode theme = RestJson.NODES.objectNode();
        theme.put("stylesheet", STYLESHEET);
        theme.put("template", STYLESHEET);
        theme.put("requires_php", "");
        theme.put("requires_wp", "");
        theme.put("textdomain", "");
        theme.put("version", "");
        theme.put("screenshot", "");
        theme.set("author", rendered("", "Anonymous"));
        theme.set("author_uri", rendered("", ""));
        theme.set("description", rendered(DESCRIPTION, DESCRIPTION));
        theme.set("name", rendered(NAME, NAME));
        ObjectNode tags = theme.putObject("tags");
        tags.putArray("raw");
        tags.put("rendered", "");
        theme.set("theme_uri", rendered("", ""));
        theme.put("status", "active");
        theme.set("theme_supports", supports.deepCopy());
        theme.put("is_block_theme", false);
        String directory = urls.home() + "wp-content/themes/" + STYLESHEET;
        theme.put("stylesheet_uri", directory);
        theme.put("template_uri", directory);
        ObjectNode lists = defaults;
        theme.set("default_template_types", lists.get("default_template_types").deepCopy());
        theme.set(
                "default_template_part_areas",
                lists.get("default_template_part_areas").deepCopy());
        return theme;
    }

    /** The theme's links: itself (the caller's target hints are added when rendered) and the collection. */
    public Links links(RestUrls urls) {
        return Links.none()
                .add("self", urls.route("/wp/v2/themes/" + STYLESHEET))
                .add("collection", urls.route("/wp/v2/themes"));
    }

    private static ObjectNode rendered(String raw, String rendered) {
        ObjectNode value = RestJson.NODES.objectNode();
        value.put("raw", raw);
        value.put("rendered", rendered);
        return value;
    }

    static ObjectNode build(RestWpConfig.Theme theme) {
        ObjectNode features = RestJson.NODES.objectNode();
        features.put("align-wide", theme.alignWide());
        features.put("automatic-feed-links", theme.automaticFeedLinks());
        features.put("block-templates", false);
        features.put("block-template-parts", false);
        features.put("custom-background", false);
        features.put("custom-header", false);
        features.put("custom-logo", false);
        features.put("customize-selective-refresh-widgets", theme.customizeSelectiveRefreshWidgets());
        features.put("dark-editor-style", theme.darkEditorStyle());
        features.put("disable-custom-colors", theme.disableCustomColors());
        features.put("disable-custom-font-sizes", theme.disableCustomFontSizes());
        features.put("disable-custom-gradients", theme.disableCustomGradients());
        features.put("disable-layout-styles", theme.disableLayoutStyles());
        features.put("editor-color-palette", false);
        features.put("editor-font-sizes", false);
        features.put("editor-gradient-presets", false);
        features.put("editor-spacing-sizes", false);
        features.put("editor-styles", theme.editorStyles());
        features.set("html5", html5(theme.html5()));
        features.set("formats", formats(theme.formats()));
        features.put("post-thumbnails", theme.postThumbnails());
        features.put("responsive-embeds", theme.responsiveEmbeds());
        features.put("title-tag", theme.titleTag());
        features.put("wp-block-styles", theme.wpBlockStyles());
        return features;
    }

    /** The configured list as given, or false for the single value {@code none}. */
    private static JsonNode html5(List<String> values) {
        if (values.contains("none")) {
            if (values.size() > 1) {
                throw new ConfigurationException(
                        "quarkpress-rest-wp-v2: " + HTML5_KEY + " lists none beside other values: none stands alone");
            }
            return BooleanNode.FALSE;
        }
        ArrayNode list = RestJson.NODES.arrayNode();
        for (String value : values) {
            if (!HTML5.contains(value)) {
                throw new ConfigurationException("quarkpress-rest-wp-v2: " + HTML5_KEY + " names " + value
                        + ", which is not one of " + String.join(", ", HTML5) + " or none");
            }
            list.add(value);
        }
        return list;
    }

    /** {@code standard} followed by the configured formats in their order. */
    private static JsonNode formats(Optional<List<String>> values) {
        ArrayNode list = RestJson.NODES.arrayNode();
        list.add("standard");
        for (String value : values.orElse(List.of())) {
            if (!FORMATS.contains(value)) {
                throw new ConfigurationException("quarkpress-rest-wp-v2: " + FORMATS_KEY + " names " + value
                        + ", which is not one of " + String.join(", ", FORMATS));
            }
            list.add(value);
        }
        return list;
    }

    private static ObjectNode readDefaults() {
        try (InputStream in = SyntheticTheme.class.getResourceAsStream(DEFAULTS)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DEFAULTS);
            }
            return (ObjectNode) RestJson.READER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
