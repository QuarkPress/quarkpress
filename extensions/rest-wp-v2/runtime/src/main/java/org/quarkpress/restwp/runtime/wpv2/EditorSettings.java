package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeRegistry;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostLocks;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.AutosaveOverlay;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.RestWpConfig;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;

/**
 * The block editor's settings object for a post, as WordPress's edit screen hands it to {@code initializeEditor}: the
 * keys in the order the screen prints them for a classic theme without a theme.json, the media keys following the caller
 * (its allowed types, its upload limit, the registered image sizes; nothing without the media module or while the site's
 * media feature is off), the theme flags from the synthetic theme's configuration, the theme.json-derived presets as
 * recorded from WordPress's core defaults (a resource file, since no theme.json exists), the per-screen keys
 * (placeholders, intervals, the lock as it stands, the link to a newer autosave), and the caller's capabilities last.
 * WordPress's style sheets, resolved assets and admin-ajax lock utilities are not served: the admin shell adds its own.
 *
 * <p>The server block definitions WordPress bootstraps beside the settings come from the blocks module's registry, each
 * with the properties WordPress's block type always holds and those it has set.
 */
@ApplicationScoped
public class EditorSettings {

    /** The file holding the recorded theme.json-derived defaults of a classic theme. */
    static final String DEFAULTS = "/org/quarkpress/restwp/editor-settings-defaults.json";

    /** The core block categories, with their titles. */
    private static final List<Map.Entry<String, String>> CATEGORIES = List.of(
            Map.entry("text", "Text"),
            Map.entry("media", "Media"),
            Map.entry("design", "Design"),
            Map.entry("widgets", "Widgets"),
            Map.entry("theme", "Theme"),
            Map.entry("embed", "Embeds"),
            Map.entry("reusable", "Patterns"));

    /** The image sizes the editor offers, with their names. */
    private static final List<Map.Entry<String, String>> IMAGE_SIZES = List.of(
            Map.entry("thumbnail", "Thumbnail"),
            Map.entry("medium", "Medium"),
            Map.entry("large", "Large"),
            Map.entry("full", "Full Size"));

    /** The discussion options the editor reads, as setting key and option name. */
    private static final List<Map.Entry<String, String>> DISCUSSION = List.of(
            Map.entry("commentOrder", "comment_order"),
            Map.entry("commentsPerPage", "comments_per_page"),
            Map.entry("defaultCommentsPage", "default_comments_page"),
            Map.entry("pageComments", "page_comments"),
            Map.entry("threadComments", "thread_comments"),
            Map.entry("threadCommentsDepth", "thread_comments_depth"),
            Map.entry("defaultCommentStatus", "default_comment_status"));

    /** WordPress's values of those options on a fresh install, for a site that has not stored them. */
    private static final Map<String, String> DISCUSSION_DEFAULTS = Map.of(
            "comment_order", "asc",
            "comments_per_page", "50",
            "default_comments_page", "newest",
            "page_comments", "0",
            "thread_comments", "1",
            "thread_comments_depth", "5",
            "default_comment_status", "open");

    private static final int LOCK_AVATAR = 128;

    private static final int DISCUSSION_AVATAR = 96;

    @Inject
    RestWpConfig config;

    @Inject
    SiteContext site;

    @Inject
    Capabilities capabilities;

    @Inject
    PostLocks locks;

    @Inject
    UserProfiles profiles;

    @Inject
    Instance<EditorMedia> media;

    @Inject
    Instance<AutosaveOverlay> autosaves;

    @Inject
    BlockTypeRegistry blocks;

    @Inject
    RestUrls urls;

    /**
     * The admin screens' path below each site, the admin module's setting: present only when a module that declares
     * it is installed (QuarkPress refuses an undeclared {@code quarkpress.*} key), so this module reads it without
     * depending on that module.
     */
    @ConfigProperty(name = ADMIN_PATH)
    Optional<String> adminPath;

    /** The key of the admin screens' path. */
    static final String ADMIN_PATH = "quarkpress.admin.path";

    /** The recorded flags read as booleans, in the order the settings print them. */
    static final List<String> FLAGS =
            List.of("disableCustomColors", "disableCustomFontSizes", "disableCustomGradients", "disableLayoutStyles");

    /** The recorded values copied as they stand, in the order the settings print them. */
    static final List<String> RECORDED = List.of(
            "enableCustomLineHeight",
            "enableCustomSpacing",
            "enableCustomUnits",
            "__experimentalFeatures",
            "colors",
            "gradients",
            "fontSizes",
            "disableCustomSpacingSizes",
            "spacingSizes");

    /** The recorded defaults, read from the class path and checked when the application starts. */
    private ObjectNode defaults;

    /** The resource is checked when the application starts, so a broken one fails the start, not the first request. */
    void start(@Observes StartupEvent started) {
        defaults = checked(readDefaults());
    }

    /** The settings object for {@code post} (of {@code type}) as the request's caller sees it. */
    public ObjectNode of(RestRequest request, PostSnapshot post, PostTypeDefinition type) {
        RestWpConfig.Theme theme = config.theme();
        RestWpConfig.Editor editor = config.editor();
        SiteOptions options = site.options();
        Optional<EditorMedia> present =
                media.isResolvable() ? Optional.of(media.get()).filter(EditorMedia::enabled) : Optional.empty();
        ObjectNode settings = RestJson.NODES.objectNode();
        settings.put("alignWide", theme.alignWide());
        settings.put("allowedBlockTypes", true);
        settings.set("allowedMimeTypes", allowedMimeTypes(present));
        settings.set("blockCategories", blockCategories());
        settings.put("isRTL", false);
        settings.put("imageDefaultSize", imageDefaultSize(options));
        imageDimensions(present).ifPresent(dimensions -> settings.set("imageDimensions", dimensions));
        settings.put("imageEditing", true);
        settings.set("imageSizes", imageSizes());
        settings.put("maxUploadFileSize", maxUploadFileSize(request, present));
        settings.put("__experimentalDashboardLink", dashboard(editor));
        settings.put("__unstableGalleryWithImageBlocks", true);
        // the recorded flags, each also on when the synthetic theme declares the feature
        settings.put("disableCustomColors", flag("disableCustomColors") || theme.disableCustomColors());
        settings.put("disableCustomFontSizes", flag("disableCustomFontSizes") || theme.disableCustomFontSizes());
        settings.put("disableCustomGradients", flag("disableCustomGradients") || theme.disableCustomGradients());
        settings.put("disableLayoutStyles", flag("disableLayoutStyles") || theme.disableLayoutStyles());
        settings.set("enableCustomLineHeight", recorded("enableCustomLineHeight"));
        settings.set("enableCustomSpacing", recorded("enableCustomSpacing"));
        settings.set("enableCustomUnits", recorded("enableCustomUnits"));
        settings.putArray("availableTemplates");
        settings.put("disablePostFormats", theme.formats().map(List::isEmpty).orElse(true));
        settings.put("titlePlaceholder", "Add title");
        settings.put("bodyPlaceholder", "Type / to choose a block");
        settings.put("autosaveInterval", editor.autosaveInterval());
        settings.put("richEditingEnabled", true);
        settings.set("postLock", postLock(request, post, options));
        settings.put("supportsLayout", false);
        settings.put("supportsTemplateMode", false);
        if (type.supports("custom-fields")) {
            settings.put("enableCustomFields", false);
        }
        settings.putArray("__experimentalAdditionalBlockPatterns");
        settings.putArray("__experimentalAdditionalBlockPatternCategories");
        autosave(post, type, editor).ifPresent(link -> settings.set("autosave", link));
        settings.putObject("__experimentalBlockBindingsSupportedAttributes");
        settings.set("__experimentalFeatures", recorded("__experimentalFeatures"));
        settings.set("colors", recorded("colors"));
        settings.set("gradients", recorded("gradients"));
        settings.set("fontSizes", recorded("fontSizes"));
        settings.set("disableCustomSpacingSizes", recorded("disableCustomSpacingSizes"));
        settings.set("spacingSizes", recorded("spacingSizes"));
        settings.put("__unstableIsBlockBasedTheme", false);
        settings.put("localAutosaveInterval", editor.localAutosaveInterval());
        settings.set("__experimentalDiscussionSettings", discussion(options));
        // the caller passed the edit check to get here, so it may bind blocks
        settings.put("canUpdateBlockBindings", true);
        settings.put("canEditCSS", capabilities.can(request.identity(), "edit_css", Optional.empty()));
        return settings;
    }

    /**
     * The server block definitions, by block name in registration order: the properties each type has set, in
     * WordPress's order; an empty object for an empty registry.
     */
    public ObjectNode blockDefinitions() {
        ObjectNode definitions = RestJson.NODES.objectNode();
        for (BlockType type : blocks.all()) {
            definitions.set(type.name(), definition(type));
        }
        return definitions;
    }

    private ObjectNode definition(BlockType type) {
        ObjectNode definition = RestJson.NODES.objectNode();
        // WordPress tests isset: the fields its block type always holds print even when empty, the rest when set
        definition.put("apiVersion", type.apiVersion());
        definition.put("title", type.title());
        definition.put("description", type.description());
        type.icon().ifPresent(icon -> definition.set("icon", BlockTypeFields.phpArrays(icon)));
        whenSet(definition, "attributes", blocks.attributes(type));
        whenSet(definition, "providesContext", type.providesContext());
        always(definition, "usesContext", type.usesContext());
        definition.set("blockHooks", BlockTypeFields.phpArrays(type.blockHooks()));
        definition.set("selectors", BlockTypeFields.phpArrays(type.selectors()));
        whenSet(definition, "supports", type.supports());
        type.category().ifPresent(category -> definition.put("category", category));
        definition.set("styles", BlockTypeFields.phpArrays(type.styles()));
        type.textdomain().ifPresent(textdomain -> definition.put("textdomain", textdomain));
        type.parent().ifPresent(parent -> always(definition, "parent", parent));
        type.ancestor().ifPresent(ancestor -> always(definition, "ancestor", ancestor));
        always(definition, "keywords", type.keywords());
        type.example().ifPresent(example -> definition.set("example", BlockTypeFields.phpArrays(example)));
        definition.set("variations", BlockTypeFields.phpArrays(type.variations()));
        type.allowedBlocks().ifPresent(allowed -> always(definition, "allowedBlocks", allowed));
        return definition;
    }

    /** A property WordPress leaves null until set: present when it holds something, printed as a PHP array. */
    private static void whenSet(ObjectNode definition, String property, JsonNode value) {
        if (!value.isEmpty()) {
            definition.set(property, BlockTypeFields.phpArrays(value));
        }
    }

    /** A list of texts, printed empty or not. */
    private static void always(ObjectNode definition, String property, List<String> values) {
        ArrayNode list = definition.putArray(property);
        values.forEach(list::add);
    }

    // ---- media keys

    /** The caller's allowed types as an object, pattern to MIME type; empty without the media module. */
    private static ObjectNode allowedMimeTypes(Optional<EditorMedia> media) {
        ObjectNode types = RestJson.NODES.objectNode();
        media.ifPresent(present -> present.allowedTypes().forEach(type -> types.put(type.getKey(), type.getValue())));
        return types;
    }

    /** The upload limit for a caller who may upload files; 0 for anyone else and without the media module. */
    private long maxUploadFileSize(RestRequest request, Optional<EditorMedia> media) {
        if (media.isEmpty() || !capabilities.can(request.identity(), "upload_files", Optional.empty())) {
            return 0;
        }
        return Math.max(0, media.get().maxUploadSize());
    }

    /**
     * The registered sub-sizes among the editor's sizes ({@code full} is never one), each with its box and crop;
     * nothing without the media module, and an empty PHP array when none of them is registered.
     */
    private static Optional<JsonNode> imageDimensions(Optional<EditorMedia> media) {
        if (media.isEmpty()) {
            return Optional.empty();
        }
        Map<String, EditorMedia.Dimensions> registered = media.get().imageSizes();
        ObjectNode dimensions = RestJson.NODES.objectNode();
        for (Map.Entry<String, String> size : IMAGE_SIZES) {
            EditorMedia.Dimensions found = registered.get(size.getKey());
            if (found != null && !size.getKey().equals("full")) {
                ObjectNode box = dimensions.putObject(size.getKey());
                box.put("width", found.width());
                box.put("height", found.height());
                box.set("crop", found.crop());
            }
        }
        return Optional.of(dimensions.isEmpty() ? RestJson.NODES.arrayNode() : dimensions);
    }

    /** The {@code image_default_size} option when it names one of the editor's sizes, else {@code large}. */
    private static String imageDefaultSize(SiteOptions options) {
        String stored = options.string("image_default_size", "");
        for (Map.Entry<String, String> size : IMAGE_SIZES) {
            if (size.getKey().equals(stored)) {
                return stored;
            }
        }
        return "large";
    }

    private static ArrayNode imageSizes() {
        ArrayNode sizes = RestJson.NODES.arrayNode();
        for (Map.Entry<String, String> size : IMAGE_SIZES) {
            ObjectNode entry = sizes.addObject();
            entry.put("slug", size.getKey());
            entry.put("name", size.getValue());
        }
        return sizes;
    }

    private static ArrayNode blockCategories() {
        ArrayNode categories = RestJson.NODES.arrayNode();
        for (Map.Entry<String, String> category : CATEGORIES) {
            ObjectNode entry = categories.addObject();
            entry.put("slug", category.getKey());
            entry.put("title", category.getValue());
            entry.putNull("icon");
        }
        return categories;
    }

    // ---- per-screen keys

    /**
     * The configured dashboard URL with its trailing slash; otherwise WordPress's {@code admin_url('/')}: the site's
     * URL followed by the admin path ({@value #ADMIN_PATH} when set, {@code wp-admin/} otherwise).
     */
    private String dashboard(RestWpConfig.Editor editor) {
        return editor.dashboardUrl()
                .map(url -> url.endsWith("/") ? url : url + "/")
                .orElseGet(() -> urls.home() + adminFolder(adminPath));
    }

    /**
     * The admin folder below a site's URL for an admin path such as {@code /a/b/}: {@code a/b/}, without leading and
     * with one trailing slash; {@code wp-admin/} when there is no path or it names no folder.
     */
    static String adminFolder(Optional<String> adminPath) {
        String folder = adminPath.orElse("").strip();
        int start = 0;
        while (start < folder.length() && folder.charAt(start) == '/') {
            start++;
        }
        folder = folder.substring(start);
        if (folder.isEmpty()) {
            return "wp-admin/";
        }
        return folder.endsWith("/") ? folder : folder + "/";
    }

    /**
     * The lock as it stands, nothing taken: another user's fresh lock names that user, with the avatar WordPress's
     * dialog shows; otherwise the post is not locked for the caller.
     */
    private ObjectNode postLock(RestRequest request, PostSnapshot post, SiteOptions options) {
        ObjectNode lock = RestJson.NODES.objectNode();
        Optional<UserId> holder = locks.holder(post.id(), request.user());
        if (holder.isEmpty()) {
            lock.put("isLocked", false);
            return lock;
        }
        Optional<UserProfile> profile = profiles.find(holder.get());
        lock.put("isLocked", true);
        ObjectNode user = lock.putObject("user");
        user.put("name", profile.map(UserProfile::displayName).orElse(""));
        Avatars.url(
                        profile.flatMap(UserProfile::email),
                        profile.map(Avatars::initialsName).orElse(""),
                        LOCK_AVATAR,
                        options)
                .ifPresent(url -> user.put("avatar", url));
        return lock;
    }

    /**
     * The link to the revision screen of the post's newest autosave, by any user, when it is newer than the post's
     * last change; nothing otherwise, for a type without revisions, or without the revisions module. An older autosave
     * is left as it is.
     */
    private Optional<ObjectNode> autosave(PostSnapshot post, PostTypeDefinition type, RestWpConfig.Editor editor) {
        if (!autosaves.isResolvable() || !type.supports("revisions")) {
            return Optional.empty();
        }
        return autosaves
                .get()
                .latestStamp(post.id())
                .filter(stamp -> stamp.writtenAt().isAfter(post.modifiedAt()))
                .map(stamp -> {
                    ObjectNode link = RestJson.NODES.objectNode();
                    link.put("editLink", dashboard(editor) + "revision.php?revision=" + stamp.id());
                    return link;
                });
    }

    /** The eight discussion options as the strings WordPress stores, and the forced default avatar at 96 pixels. */
    private static ObjectNode discussion(SiteOptions options) {
        ObjectNode discussion = RestJson.NODES.objectNode();
        for (Map.Entry<String, String> option : DISCUSSION) {
            String name = option.getValue();
            discussion.put(option.getKey(), options.string(name, DISCUSSION_DEFAULTS.get(name)));
        }
        discussion.put("avatarURL", Avatars.forcedDefault(DISCUSSION_AVATAR, options));
        return discussion;
    }

    // ---- recorded defaults

    private JsonNode recorded(String key) {
        return defaults.get(key).deepCopy();
    }

    private boolean flag(String key) {
        return defaults.get(key).booleanValue();
    }

    /** {@code defaults} with every recorded key present and every flag a boolean; else the start fails naming the key. */
    static ObjectNode checked(ObjectNode defaults) {
        for (String key : FLAGS) {
            if (!defaults.path(key).isBoolean()) {
                throw new IllegalStateException(DEFAULTS + " must hold the boolean " + key);
            }
        }
        for (String key : RECORDED) {
            if (!defaults.has(key)) {
                throw new IllegalStateException(DEFAULTS + " must hold " + key);
            }
        }
        return defaults;
    }

    private static ObjectNode readDefaults() {
        try (InputStream in = EditorSettings.class.getResourceAsStream(DEFAULTS)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DEFAULTS);
            }
            return (ObjectNode) RestJson.READER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
