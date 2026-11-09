package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaValidator;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * The item schema and the arguments of the media routes, as WordPress's attachments controller declares them on top of
 * its posts controller's: the attachment's posts schema without {@code password}, with the attachment's own
 * properties after it; the collection's arguments without the posts-only filters, with the attachment statuses and
 * the type filters; the write arguments with the attachment's text fields. Takes plain values only, so it loads
 * without the media module.
 */
final class MediaSchema {

    /** The top-level MIME types in the order WordPress's default type list first names them. */
    static final List<String> MEDIA_TYPES = List.of("image", "video", "audio", "text", "application");

    /** The statuses an attachment can be listed by, the first being the collection's default. */
    static final List<String> STATUSES = List.of("inherit", "private", "trash");

    private static final List<String> ALL = List.of("view", "edit", "embed");
    private static final List<String> VIEW_EDIT = List.of("view", "edit");
    private static final List<String> EDIT = List.of("edit");

    /** The posts collection arguments the media collection leaves out, besides the term filters. */
    private static final Set<String> NOT_MEDIA_ARGS =
            Set.of("menu_order", "sticky", "ignore_sticky", "format", TermFilterArgs.TAX_RELATION);

    private static final String ACTION = "https://api.w.org/action-";

    private MediaSchema() {}

    /**
     * The item schema: {@code routes}' posts schema for {@code attachment} without {@code password}, then the
     * attachment's properties in WordPress's order with their types, contexts and flags. The image sizes of
     * {@code image_quality} are the current site's, filled in by {@link #withSizes}.
     */
    static ObjectNode of(PostRoutes routes) {
        ObjectNode schema = routes.schema().deepCopy();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.remove("password");
        property(
                properties, "alt_text", "Alternative text to display when attachment is not displayed.", "string", ALL);
        text(
                properties,
                "caption",
                "The attachment caption.",
                ALL,
                "Caption for the attachment, as it exists in the database.",
                "HTML caption for the attachment, transformed for display.",
                ALL);
        text(
                properties,
                "description",
                "The attachment description.",
                VIEW_EDIT,
                "Description for the attachment, as it exists in the database.",
                "HTML description for the attachment, transformed for display.",
                VIEW_EDIT);
        ObjectNode mediaType = describe(properties, "media_type", "Attachment type.");
        mediaType.put("type", "string");
        mediaType.putArray("enum").add("image").add("file");
        readonly(mediaType, ALL);
        readonly(property(properties, "mime_type", "The attachment MIME type.", "string", ALL));
        readonly(property(
                properties, "media_details", "Details about the media file, specific to its type.", "object", ALL));
        property(properties, "post", "The ID for the associated post of the attachment.", "integer", VIEW_EDIT);
        ObjectNode source = describe(properties, "source_url", "URL to the original attachment file.");
        source.put("type", "string");
        source.put("format", "uri");
        readonly(source, ALL);
        ObjectNode missing =
                describe(properties, "missing_image_sizes", "List of the missing image sizes of the attachment.");
        missing.put("type", "array");
        missing.putObject("items").put("type", "string");
        readonly(missing, EDIT);
        readonly(property(properties, "filename", "Original attachment file name.", "string", VIEW_EDIT));
        ObjectNode filesize = describe(properties, "filesize", "Attachment file size in bytes.");
        filesize.putArray("type").add("integer").add("null");
        readonly(filesize, VIEW_EDIT);
        readonly(property(
                properties,
                "exif_orientation",
                "EXIF orientation value. Values 1-8 follow the EXIF specification, where 1 means no rotation needed.",
                "integer",
                EDIT));
        ObjectNode quality = property(
                properties,
                "image_quality",
                "Encode quality (1-100) from the wp_editor_set_quality filter, resolved against the output MIME type."
                        + " The \"default\" value applies to the full-size image; \"sizes\" lists per-registered-size"
                        + " overrides where the filtered value differs from \"default\".",
                "object",
                EDIT);
        quality.put("readonly", true);
        ObjectNode qualities = quality.putObject("properties");
        percent(qualities.putObject("default"));
        ObjectNode sizes = qualities.putObject("sizes");
        sizes.put("type", "object");
        sizes.putObject("properties");
        ObjectNode format = describe(
                properties,
                "image_output_format",
                "The output MIME type this image should be converted to, based on the image_editor_output_format"
                        + " filter. Null if no conversion is needed.");
        format.putArray("type").add("string").add("null");
        readonly(format, EDIT);
        readonly(property(
                properties,
                "image_save_progressive",
                "Whether to use progressive/interlaced encoding when saving this image.",
                "boolean",
                EDIT));
        return schema;
    }

    /** A copy of {@code schema} whose {@code image_quality} lists {@code sizes} (the site's sizes, in order). */
    static ObjectNode withSizes(ObjectNode schema, Collection<String> sizes) {
        ObjectNode copy = schema.deepCopy();
        ObjectNode properties = (ObjectNode) copy.at("/properties/image_quality/properties/sizes/properties");
        for (String size : sizes) {
            percent(properties.putObject(size));
        }
        return copy;
    }

    /**
     * The schema with its {@code links} for the current site: unfiltered HTML, and assign author (the attachment has
     * an author); attachments are never published, so no publish action.
     */
    static ObjectNode withLinks(PostRoutes routes, RestUrls urls) {
        ObjectNode schema = routes.schema().deepCopy();
        ArrayNode links = schema.putArray("links");
        String href = urls.route(routes.collection() + "/{id}");
        action(links, "unfiltered-html", "The current user can post unfiltered HTML markup and JavaScript.", href)
                .putObject("content")
                .putObject("raw")
                .put("type", "string");
        action(links, "assign-author", "The current user can change the author on this post.", href)
                .putObject("author")
                .put("type", "integer");
        return schema;
    }

    /**
     * The collection's arguments in WordPress's order: the posts collection's without {@code menu_order},
     * {@code sticky}, {@code ignore_sticky}, {@code format} and the term filters, with {@code parent} and
     * {@code parent_exclude} although attachments are flat, {@code status} of the attachment statuses (default
     * {@code inherit}, which needs no capability), then {@code media_type} (the top-level types of {@code allowed})
     * and {@code mime_type}. A {@code media_type} value is checked against the top-level types of the types the
     * caller may upload on the request's site ({@code current}), which a network's list of upload types narrows, as
     * WordPress declares the argument for each request; without them (no site, or media off) against {@code allowed}'s.
     *
     * @param allowed the upload types whose top-level types {@code media_type} declares, as patterns with MIME types
     * @param current the request's caller's upload types on its site, when they can be read
     */
    static List<Arg> collectionArgs(
            PostRoutes routes,
            Supplier<SecurityIdentity> caller,
            PostPermissions permissions,
            List<Map.Entry<String, String>> allowed,
            Supplier<Optional<List<Map.Entry<String, String>>>> current) {
        Set<String> left = new HashSet<>(NOT_MEDIA_ARGS);
        TermFilterArgs.args(routes.taxonomies()).forEach(arg -> left.add(arg.name()));
        List<Arg> args = new ArrayList<>();
        for (Arg arg : PostSchema.collectionArgs(routes, List.of(), caller, permissions)) {
            if (left.contains(arg.name()) || arg.name().startsWith("parent")) {
                continue;
            }
            if (arg.name().equals("status")) {
                args.add(PostSchema.statusArg(routes.key(), STATUSES.get(0), STATUSES, caller, permissions));
                continue;
            }
            args.add(arg);
            if (arg.name().equals("orderby")) {
                args.add(PostSchema.idList("parent", "Limit result set to items with particular parent IDs."));
                args.add(PostSchema.idList(
                        "parent_exclude", "Limit result set to all items except those of a particular parent ID."));
            }
        }
        ObjectNode mediaType = mediaTypeSchema(allowed);
        Predicate<JsonNode> check = value -> {
            ObjectNode schema = current.get().map(MediaSchema::mediaTypeSchema).orElse(mediaType);
            Optional<RestError> refused = SchemaValidator.validate(value, schema, "media_type");
            if (refused.isPresent()) {
                throw new RestException(refused.get());
            }
            return true;
        };
        Arg declared = PostSchema.query("media_type", mediaType);
        args.add(new Arg(
                declared.name(),
                declared.schema(),
                declared.required(),
                declared.defaultValue(),
                declared.phase(),
                declared.sanitize(),
                Optional.of(check)));
        ObjectNode mimeType = RestJson.NODES.objectNode();
        mimeType.putNull("default");
        mimeType.put("description", "Limit result set to attachments of a particular MIME type or MIME types.");
        mimeType.put("type", "array");
        mimeType.putObject("items").put("type", "string");
        args.add(PostSchema.query("mime_type", mimeType));
        return List.copyOf(args);
    }

    /** The schema of {@code media_type}: a list of the top-level types of {@code allowed}. */
    private static ObjectNode mediaTypeSchema(List<Map.Entry<String, String>> allowed) {
        ObjectNode mediaType = RestJson.NODES.objectNode();
        mediaType.putNull("default");
        mediaType.put("description", "Limit result set to attachments of a particular media type or media types.");
        mediaType.put("type", "array");
        ObjectNode type = mediaType.putObject("items");
        type.put("type", "string");
        ArrayNode types = type.putArray("enum");
        mediaTypes(allowed).forEach(types::add);
        return mediaType;
    }

    /**
     * The top-level types of {@code allowed}'s MIME types: those of WordPress's default list in its order
     * ({@link #MEDIA_TYPES}), whatever order {@code allowed} names them in, then any other in order of first
     * appearance.
     */
    static List<String> mediaTypes(List<Map.Entry<String, String>> allowed) {
        Set<String> present = new LinkedHashSet<>();
        for (Map.Entry<String, String> type : allowed) {
            String mime = type.getValue();
            int slash = mime.indexOf('/');
            present.add(slash < 0 ? mime : mime.substring(0, slash));
        }
        List<String> ordered = new ArrayList<>();
        for (String known : MEDIA_TYPES) {
            if (present.remove(known)) {
                ordered.add(known);
            }
        }
        ordered.addAll(present);
        return List.copyOf(ordered);
    }

    /**
     * The create arguments in WordPress's order: the schema's writable properties ({@link #writeArgs}), then
     * {@code generate_sub_sizes} and {@code convert_format}, booleans defaulting to true.
     */
    static List<Arg> createArgs(PostRoutes routes) {
        List<Arg> args = new ArrayList<>(writeArgs(routes, Optional::empty));
        args.add(flag("generate_sub_sizes", "Whether to generate image sub sizes."));
        args.add(flag("convert_format", "Whether to convert image formats."));
        return List.copyOf(args);
    }

    /** The update arguments: the schema's writable properties ({@link #writeArgs}), without defaults. */
    static List<Arg> updateArgs(PostRoutes routes, Supplier<Optional<PostSnapshot>> stored) {
        return writeArgs(routes, stored);
    }

    /**
     * The posts write arguments of the attachment's schema, with the attachment's own rules: {@code caption} and
     * {@code description} take a string or an object and are neither checked nor sanitized, as {@code title};
     * {@code alt_text} must be a string and is sanitized as a text field; {@code post} is an integer.
     */
    private static List<Arg> writeArgs(PostRoutes routes, Supplier<Optional<PostSnapshot>> stored) {
        List<Arg> args = new ArrayList<>();
        for (Arg arg : PostSchema.writeArgs(routes, stored)) {
            args.add(
                    switch (arg.name()) {
                        case "caption", "description" ->
                            new Arg(
                                    arg.name(),
                                    arg.schema(),
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.of(UnaryOperator.identity()),
                                    Optional.of(value -> true));
                        case "alt_text" ->
                            new Arg(
                                    arg.name(),
                                    arg.schema(),
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.of(MediaSchema::textField));
                        default -> arg;
                    });
        }
        return List.copyOf(args);
    }

    /** A text sanitized as a text field; any other value as it is (the schema refused it before). */
    private static JsonNode textField(JsonNode value) {
        return value.isTextual() ? TextNode.valueOf(TextFields.textField(value.textValue())) : value;
    }

    private static Arg flag(String name, String description) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("type", "boolean");
        schema.put("default", true);
        schema.put("description", description);
        return new Arg(
                name,
                schema,
                false,
                Optional.of(RestJson.NODES.booleanNode(true)),
                Arg.Phase.VALIDATE,
                Optional.empty());
    }

    /** The post-process endpoint's {@code id}: an integer, checked while the arguments are sanitized. */
    static Arg postProcessIdArg() {
        ObjectNode id = RestJson.NODES.objectNode();
        id.put("description", "Unique identifier for the attachment.");
        id.put("type", "integer");
        return new Arg("id", id, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** The post-process endpoint's {@code action}: required, {@code create-image-subsizes} only. */
    static Arg actionArg() {
        ObjectNode action = RestJson.NODES.objectNode();
        action.put("type", "string");
        action.putArray("enum").add("create-image-subsizes");
        return new Arg("action", action, true, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty());
    }

    /** Adds a link; answers its target schema's {@code properties}. */
    private static ObjectNode action(ArrayNode links, String action, String title, String href) {
        ObjectNode link = links.addObject();
        link.put("rel", ACTION + action);
        link.put("title", title);
        link.put("href", href);
        ObjectNode target = link.putObject("targetSchema");
        target.put("type", "object");
        return target.putObject("properties");
    }

    /** An object of a raw text and its rendered HTML, as the caption and the description are. */
    private static void text(
            ObjectNode properties,
            String name,
            String description,
            List<String> contexts,
            String raw,
            String rendered,
            List<String> renderedContexts) {
        ObjectNode text = property(properties, name, description, "object", contexts);
        ObjectNode parts = text.putObject("properties");
        property(parts, "raw", raw, "string", EDIT);
        readonly(property(parts, "rendered", rendered, "string", renderedContexts));
    }

    private static void percent(ObjectNode property) {
        property.put("type", "integer");
        property.put("minimum", 1);
        property.put("maximum", 100);
    }

    private static ObjectNode describe(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        return property;
    }

    private static ObjectNode property(
            ObjectNode properties, String name, String description, String type, List<String> contexts) {
        ObjectNode property = describe(properties, name, description);
        property.put("type", type);
        contexts(property, contexts);
        return property;
    }

    private static ObjectNode readonly(ObjectNode property) {
        property.put("readonly", true);
        return property;
    }

    private static void readonly(ObjectNode property, List<String> contexts) {
        contexts(property, contexts);
        property.put("readonly", true);
    }

    private static void contexts(ObjectNode property, List<String> contexts) {
        ArrayNode list = property.putArray("context");
        contexts.forEach(list::add);
    }
}
