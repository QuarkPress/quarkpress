package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.Slugs;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;
import org.quarkpress.taxonomy.PostFormats;

/**
 * The item schema and the arguments of a post type's routes, as WordPress's posts controller declares them: the
 * properties a type has follow its features ({@link #supports}), its hierarchy, its public visibility and the
 * taxonomies shown in REST for it; the collection's arguments follow the same features.
 */
final class PostSchema {

    static final String ID = "Unique identifier for the post.";

    /** The posts route's own features, whatever the registered type says. */
    private static final List<String> POST_SUPPORTS = List.of(
            "title",
            "editor",
            "author",
            "excerpt",
            "thumbnail",
            "comments",
            "revisions",
            "post-formats",
            "custom-fields");

    /** The pages route's own features. */
    private static final List<String> PAGE_SUPPORTS = List.of(
            "title",
            "editor",
            "author",
            "excerpt",
            "thumbnail",
            "comments",
            "revisions",
            "page-attributes",
            "custom-fields");

    private static final List<String> ALL = List.of("view", "edit", "embed");
    private static final List<String> VIEW_EDIT = List.of("view", "edit");
    private static final List<String> EDIT = List.of("edit");

    private static final List<String> ORDERBY = List.of(
            "author", "date", "id", "include", "modified", "parent", "relevance", "slug", "include_slugs", "title");

    private static final String ACTION = "https://api.w.org/action-";

    private PostSchema() {}

    /** The features whose fields the type's routes carry: fixed lists for posts and pages, else the type's own. */
    static List<String> supports(PostTypeDefinition type) {
        return switch (type.key()) {
            case "post" -> POST_SUPPORTS;
            case "page" -> PAGE_SUPPORTS;
            default -> type.supports();
        };
    }

    /**
     * The item schema: the properties the type has, in WordPress's order, with their contexts and flags.
     *
     * @param metaSchema the {@code meta} property, for a type with custom fields
     * @param statuses the statuses a post may be given (the registered ones that are not internal)
     */
    static ObjectNode of(
            PostTypeDefinition type,
            List<TaxonomyDefinition> taxonomies,
            ObjectNode metaSchema,
            List<String> statuses) {
        List<String> supports = supports(type);
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", type.key());
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode date = property(properties, "date", "The date the post was published, in the site's timezone.");
        date.putArray("type").add("string").add("null");
        date.put("format", "date-time");
        contexts(date, ALL);
        ObjectNode dateGmt = property(properties, "date_gmt", "The date the post was published, as GMT.");
        dateGmt.putArray("type").add("string").add("null");
        dateGmt.put("format", "date-time");
        contexts(dateGmt, VIEW_EDIT);
        ObjectNode guid =
                property(properties, "guid", "The globally unique identifier for the post.", "object", VIEW_EDIT);
        guid.put("readonly", true);
        ObjectNode guidProperties = guid.putObject("properties");
        property(guidProperties, "raw", "GUID for the post, as it exists in the database.", "string", EDIT)
                .put("readonly", true);
        property(guidProperties, "rendered", "GUID for the post, transformed for display.", "string", VIEW_EDIT)
                .put("readonly", true);
        property(properties, "id", ID, "integer", ALL).put("readonly", true);
        ObjectNode link = property(properties, "link", "URL to the post.");
        link.put("type", "string");
        link.put("format", "uri");
        contexts(link, ALL);
        link.put("readonly", true);
        dateProperty(properties, "modified", "The date the post was last modified, in the site's timezone.");
        dateProperty(properties, "modified_gmt", "The date the post was last modified, as GMT.");
        property(properties, "slug", "An alphanumeric identifier for the post unique to its type.", "string", ALL);
        ObjectNode status = property(properties, "status", "A named status for the post.");
        status.put("type", "string");
        ArrayNode statusEnum = status.putArray("enum");
        statuses.forEach(statusEnum::add);
        contexts(status, VIEW_EDIT);
        property(properties, "type", "Type of post.", "string", ALL).put("readonly", true);
        property(properties, "password", "A password to protect access to the content and excerpt.", "string", EDIT);
        if (PostPermissions.typeViewable(type) && type.publicly()) {
            property(properties, "permalink_template", "Permalink template for the post.", "string", EDIT)
                    .put("readonly", true);
            property(properties, "generated_slug", "Slug automatically generated from the post title.", "string", EDIT)
                    .put("readonly", true);
            ObjectNode classList = property(
                    properties,
                    "class_list",
                    "An array of the class names for the post container element.",
                    "array",
                    VIEW_EDIT);
            classList.put("readonly", true);
            classList.putObject("items").put("type", "string");
        }
        if (type.hierarchical()) {
            property(properties, "parent", "The ID for the parent of the post.", "integer", VIEW_EDIT);
        }
        if (supports.contains("title")) {
            ObjectNode title = property(properties, "title", "The title for the post.", "object", ALL);
            ObjectNode titleProperties = title.putObject("properties");
            property(titleProperties, "raw", "Title for the post, as it exists in the database.", "string", EDIT);
            property(titleProperties, "rendered", "HTML title for the post, transformed for display.", "string", ALL)
                    .put("readonly", true);
        }
        if (supports.contains("editor")) {
            ObjectNode content = property(properties, "content", "The content for the post.", "object", VIEW_EDIT);
            ObjectNode contentProperties = content.putObject("properties");
            property(contentProperties, "raw", "Content for the post, as it exists in the database.", "string", EDIT);
            property(
                            contentProperties,
                            "rendered",
                            "HTML content for the post, transformed for display.",
                            "string",
                            VIEW_EDIT)
                    .put("readonly", true);
            property(
                            contentProperties,
                            "block_version",
                            "Version of the content block format used by the post.",
                            "integer",
                            EDIT)
                    .put("readonly", true);
            property(
                            contentProperties,
                            "protected",
                            "Whether the content is protected with a password.",
                            "boolean",
                            ALL)
                    .put("readonly", true);
        }
        if (supports.contains("author")) {
            property(properties, "author", "The ID for the author of the post.", "integer", ALL);
        }
        if (supports.contains("excerpt")) {
            ObjectNode excerpt = property(properties, "excerpt", "The excerpt for the post.", "object", ALL);
            ObjectNode excerptProperties = excerpt.putObject("properties");
            property(excerptProperties, "raw", "Excerpt for the post, as it exists in the database.", "string", EDIT);
            property(
                            excerptProperties,
                            "rendered",
                            "HTML excerpt for the post, transformed for display.",
                            "string",
                            ALL)
                    .put("readonly", true);
            property(
                            excerptProperties,
                            "protected",
                            "Whether the excerpt is protected with a password.",
                            "boolean",
                            ALL)
                    .put("readonly", true);
        }
        if (supports.contains("thumbnail")) {
            property(properties, "featured_media", "The ID of the featured media for the post.", "integer", ALL);
        }
        if (supports.contains("comments")) {
            ObjectNode comments =
                    property(properties, "comment_status", "Whether or not comments are open on the post.");
            comments.put("type", "string");
            comments.putArray("enum").add("open").add("closed");
            contexts(comments, VIEW_EDIT);
            ObjectNode pings = property(properties, "ping_status", "Whether or not the post can be pinged.");
            pings.put("type", "string");
            pings.putArray("enum").add("open").add("closed");
            contexts(pings, VIEW_EDIT);
        }
        if (supports.contains("page-attributes")) {
            property(
                    properties,
                    "menu_order",
                    "The order of the post in relation to other posts.",
                    "integer",
                    VIEW_EDIT);
        }
        if (supports.contains("post-formats")) {
            ObjectNode format = property(properties, "format", "The format for the post.");
            format.put("type", "string");
            ArrayNode formats = format.putArray("enum");
            PostFormats.SLUGS.forEach(formats::add);
            contexts(format, VIEW_EDIT);
        }
        if (supports.contains("custom-fields")) {
            properties.set("meta", metaSchema.deepCopy());
        }
        if (type.key().equals("post")) {
            property(
                    properties, "sticky", "Whether or not the post should be treated as sticky.", "boolean", VIEW_EDIT);
        }
        property(properties, "template", "The theme file to use to display the post.", "string", VIEW_EDIT);
        for (TaxonomyDefinition taxonomy : taxonomies) {
            ObjectNode terms = property(
                    properties,
                    PostRoutes.base(taxonomy),
                    "The terms assigned to the post in the " + taxonomy.key() + " taxonomy.");
            terms.put("type", "array");
            terms.putObject("items").put("type", "integer");
            contexts(terms, VIEW_EDIT);
        }
        return schema;
    }

    /**
     * The schema with its {@code links} (the actions a caller may hold on an item) for the current site: publish,
     * unfiltered HTML, sticky (posts), assign author (types with an author), then per taxonomy assign and create.
     */
    static ObjectNode withLinks(PostRoutes routes, RestUrls urls) {
        ObjectNode schema = routes.schema().deepCopy();
        ArrayNode links = schema.putArray("links");
        String href = urls.route(routes.collection() + "/{id}");
        ObjectNode publish = action(links, "publish", "The current user can publish this post.", href);
        ObjectNode status = publish.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("publish").add("future");
        ObjectNode unfiltered = action(
                links, "unfiltered-html", "The current user can post unfiltered HTML markup and JavaScript.", href);
        unfiltered.putObject("content").putObject("raw").put("type", "string");
        if (routes.key().equals("post")) {
            action(links, "sticky", "The current user can sticky this post.", href)
                    .putObject("sticky")
                    .put("type", "boolean");
        }
        if (routes.supports("author")) {
            action(links, "assign-author", "The current user can change the author on this post.", href)
                    .putObject("author")
                    .put("type", "integer");
        }
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            String base = PostRoutes.base(taxonomy);
            termsAction(
                    links,
                    "assign-" + base,
                    "The current user can assign terms in the " + taxonomy.key() + " taxonomy.",
                    href,
                    base);
            termsAction(
                    links,
                    "create-" + base,
                    "The current user can create terms in the " + taxonomy.key() + " taxonomy.",
                    href,
                    base);
        }
        return schema;
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

    private static void termsAction(ArrayNode links, String action, String title, String href, String base) {
        ObjectNode terms = action(links, action, title, href).putObject(base);
        terms.put("type", "array");
        terms.putObject("items").put("type", "integer");
    }

    /**
     * The collection's arguments in WordPress's order: context, page, per_page, search, after, modified_after,
     * author and author_exclude (types with an author), before, modified_before, exclude, include, menu_order (page
     * attributes), search_semantics, offset, order, orderby, parent and parent_exclude (hierarchical types),
     * search_columns, slug, status (its own permission-checked sanitizer), the term filters of the type's REST
     * taxonomies, then for posts sticky and ignore_sticky, and format for types with post formats. Those after search
     * are checked while the arguments are sanitized.
     *
     * @param allStatuses every registered status, the values {@code status} may name next to {@code any}
     * @param caller the caller whose capabilities decide which statuses may be asked for
     */
    static List<Arg> collectionArgs(
            PostRoutes routes,
            List<String> allStatuses,
            Supplier<SecurityIdentity> caller,
            PostPermissions permissions) {
        List<Arg> args = new ArrayList<>(Paging.collectionArgs(routes.schema(), false));
        args.add(dateArg("after", "Limit response to posts published after a given ISO8601 compliant date."));
        args.add(dateArg("modified_after", "Limit response to posts modified after a given ISO8601 compliant date."));
        if (routes.supports("author")) {
            args.add(idList("author", "Limit result set to posts assigned to specific authors."));
            args.add(idList("author_exclude", "Ensure result set excludes posts assigned to specific authors."));
        }
        args.add(dateArg("before", "Limit response to posts published before a given ISO8601 compliant date."));
        args.add(dateArg("modified_before", "Limit response to posts modified before a given ISO8601 compliant date."));
        args.add(idList("exclude", "Ensure result set excludes specific IDs."));
        args.add(idList("include", "Limit result set to specific IDs."));
        if (routes.supports("page-attributes")) {
            ObjectNode menuOrder = RestJson.NODES.objectNode();
            menuOrder.put("description", "Limit result set to posts with a specific menu_order value.");
            menuOrder.put("type", "integer");
            args.add(query("menu_order", menuOrder));
        }
        ObjectNode semantics = RestJson.NODES.objectNode();
        semantics.put("description", "How to interpret the search input.");
        semantics.put("type", "string");
        semantics.putArray("enum").add("exact");
        args.add(query("search_semantics", semantics));
        args.add(Paging.offsetArg());
        ObjectNode order = RestJson.NODES.objectNode();
        order.put("description", "Order sort attribute ascending or descending.");
        order.put("type", "string");
        order.put("default", "desc");
        order.putArray("enum").add("asc").add("desc");
        args.add(query("order", order));
        ObjectNode orderby = RestJson.NODES.objectNode();
        orderby.put("description", "Sort collection by post attribute.");
        orderby.put("type", "string");
        orderby.put("default", "date");
        ArrayNode keys = orderby.putArray("enum");
        ORDERBY.forEach(keys::add);
        if (routes.supports("page-attributes")) {
            keys.add("menu_order");
        }
        args.add(query("orderby", orderby));
        if (routes.type().hierarchical()) {
            args.add(idList("parent", "Limit result set to items with particular parent IDs."));
            args.add(idList("parent_exclude", "Limit result set to all items except those of a particular parent ID."));
        }
        ObjectNode searchColumns = RestJson.NODES.objectNode();
        searchColumns.putArray("default");
        searchColumns.put("description", "Array of column names to be searched.");
        searchColumns.put("type", "array");
        ObjectNode column = searchColumns.putObject("items");
        column.putArray("enum").add("post_title").add("post_content").add("post_excerpt");
        column.put("type", "string");
        args.add(query("search_columns", searchColumns));
        ObjectNode slug = RestJson.NODES.objectNode();
        slug.put("description", "Limit result set to posts with one or more specific slugs.");
        slug.put("type", "array");
        slug.putObject("items").put("type", "string");
        args.add(query("slug", slug));
        args.add(statusArg(routes.key(), allStatuses, caller, permissions));
        args.addAll(TermFilterArgs.args(routes.taxonomies()));
        if (routes.key().equals("post")) {
            ObjectNode sticky = RestJson.NODES.objectNode();
            sticky.put("description", "Limit result set to items that are sticky.");
            sticky.put("type", "boolean");
            args.add(query("sticky", sticky));
            ObjectNode ignoreSticky = RestJson.NODES.objectNode();
            ignoreSticky.put("description", "Whether to ignore sticky posts or not.");
            ignoreSticky.put("type", "boolean");
            ignoreSticky.put("default", true);
            args.add(query("ignore_sticky", ignoreSticky));
        }
        if (routes.supports("post-formats")) {
            ObjectNode format = RestJson.NODES.objectNode();
            format.put("description", "Limit result set to items assigned one or more given formats.");
            format.put("type", "array");
            format.put("uniqueItems", true);
            ObjectNode item = format.putObject("items");
            ArrayNode formats = item.putArray("enum");
            PostFormats.SLUGS.forEach(formats::add);
            item.put("type", "string");
            args.add(query("format", format));
        }
        return List.copyOf(args);
    }

    /**
     * WordPress's {@code status} argument: a slug list (each item title-sanitized, empty ones dropped, each kept once)
     * whose sanitizer checks, for every status but the route's default {@code publish}, that the caller holds the
     * type's {@code edit_posts} (else the value is refused with {@code rest_forbidden_status} and the caller's auth
     * code), then that the status is one of the registered ones or {@code any}. WordPress also admits {@code private}
     * on {@code read_private_posts} alone; the posts module lists no status but publish without {@code edit_posts},
     * so the sanitizer asks for the same and such a caller is refused here, not by the query. A list over
     * {@value ListArgs#MAX_ITEMS} entries is refused as a list over its bound.
     */
    static Arg statusArg(
            String type, List<String> allStatuses, Supplier<SecurityIdentity> caller, PostPermissions permissions) {
        ObjectNode status = RestJson.NODES.objectNode();
        status.put("default", "publish");
        status.put("description", "Limit result set to posts assigned one or more statuses.");
        status.put("type", "array");
        ObjectNode item = status.putObject("items");
        ArrayNode values = item.putArray("enum");
        allStatuses.forEach(values::add);
        values.add("any");
        item.put("type", "string");
        return new Arg(
                "status",
                status,
                false,
                Optional.of(TextNode.valueOf("publish")),
                Arg.Phase.SANITIZE,
                Optional.of(value -> sanitizeStatus(value, item, type, caller.get(), permissions)));
    }

    private static JsonNode sanitizeStatus(
            JsonNode value, ObjectNode itemSchema, String type, SecurityIdentity caller, PostPermissions permissions) {
        requireBounded("status", value);
        List<String> statuses = ListArgs.slugList(value, Slugs::sanitizeTitle);
        ArrayNode sanitized = RestJson.NODES.arrayNode();
        for (int i = 0; i < statuses.size(); i++) {
            String status = statuses.get(i);
            if (!status.equals("publish")) {
                if (!permissions.canType(caller, "edit_posts", type)) {
                    throw new RestException(RestError.of(
                            "rest_forbidden_status", "Status is forbidden.", caller.isAnonymous() ? 401 : 403));
                }
                Optional<RestError> invalid =
                        SchemaValidator.validate(TextNode.valueOf(status), itemSchema, "status[" + i + "]");
                if (invalid.isPresent()) {
                    throw new RestException(invalid.get());
                }
            }
            sanitized.add(status);
        }
        return sanitized;
    }

    /** Refuses a list argument's value over {@value ListArgs#MAX_ITEMS} entries, as a list over its bound. */
    private static void requireBounded(String name, JsonNode value) {
        int count;
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isContainerNode()) {
            count = value.size();
        } else {
            count = PhpValues.parseList(value.asText()).size();
        }
        if (count > ListArgs.MAX_ITEMS) {
            throw new RestException(overBound(name, count));
        }
    }

    /** The schema's error for a list of {@code count} entries, over {@value ListArgs#MAX_ITEMS}. */
    private static RestError overBound(String name, int count) {
        ObjectNode bound = RestJson.NODES.objectNode();
        bound.put("type", "array");
        bound.put("maxItems", ListArgs.MAX_ITEMS);
        ArrayNode list = RestJson.NODES.arrayNode();
        for (int i = 0; i < count; i++) {
            list.add("");
        }
        return SchemaValidator.validate(list, bound, name).orElseThrow();
    }

    /**
     * The write arguments: the schema's writable properties in its order, each checked with the others before any is
     * sanitized, with WordPress's own rules where it has them: {@code slug} is sanitized as a title; {@code title},
     * {@code content} and {@code excerpt} take a string or an object and are neither checked nor sanitized;
     * {@code status} passes when it equals the stored post's status, else it must be one of the schema's;
     * {@code template} passes when it is empty in PHP's sense or equals the stored post's template, else it must be
     * one of the templates offered for the type (none without a theme: "template is not one of ."); {@code meta}
     * must be an object or a list and is not sanitized here; a taxonomy's term ids are kept once each, at most
     * {@value ListArgs#MAX_ITEMS} distinct ones.
     *
     * @param stored the post being changed, read when a value is checked; empty for a create
     */
    static List<Arg> writeArgs(PostRoutes routes, Supplier<Optional<PostSnapshot>> stored) {
        List<Arg> args = new ArrayList<>();
        Set<String> termBases = new HashSet<>();
        routes.taxonomies().forEach(taxonomy -> termBases.add(PostRoutes.base(taxonomy)));
        for (Iterator<Map.Entry<String, JsonNode>> it =
                        routes.schema().get("properties").fields();
                it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            ObjectNode schema = ((ObjectNode) entry.getValue()).deepCopy();
            if (schema.path("readonly").asBoolean(false)) {
                continue;
            }
            schema.remove("context");
            schema.remove("readonly");
            String name = entry.getKey();
            args.add(
                    switch (name) {
                        case "slug" ->
                            write(
                                    name,
                                    schema,
                                    Optional.of(value -> value.isTextual()
                                            ? TextNode.valueOf(Slugs.sanitizeTitle(value.textValue()))
                                            : value));
                        case "title", "content", "excerpt" ->
                            new Arg(
                                    name,
                                    schema,
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.of(UnaryOperator.identity()),
                                    Optional.of(value -> true));
                        case "status" ->
                            new Arg(
                                    name,
                                    schema,
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.empty(),
                                    Optional.of(value -> validStatus(value, schema, stored)));
                        case "template" ->
                            new Arg(
                                    name,
                                    schema,
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.empty(),
                                    Optional.of(value -> validTemplate(value, stored)));
                        case "meta" ->
                            new Arg(
                                    name,
                                    schema,
                                    false,
                                    Optional.empty(),
                                    Arg.Phase.VALIDATE,
                                    Optional.of(UnaryOperator.identity()),
                                    Optional.of(PhpValues::isPhpArray));
                        default ->
                            termBases.contains(name)
                                    ? write(name, schema, Optional.of(value -> termIds(name, value, schema)))
                                    : write(name, schema, Optional.empty());
                    });
        }
        return List.copyOf(args);
    }

    /**
     * A term id list sanitized by its schema, each id kept once in the order given; more than
     * {@value ListArgs#MAX_ITEMS} distinct ids are refused as a list over its bound.
     */
    private static JsonNode termIds(String name, JsonNode value, ObjectNode schema) {
        JsonNode sanitized = SchemaSanitizer.sanitize(value, schema, name);
        if (!sanitized.isArray()) {
            return sanitized;
        }
        Set<Long> distinct = new LinkedHashSet<>();
        sanitized.forEach(id -> distinct.add(PhpValues.toLong(id)));
        if (distinct.size() > ListArgs.MAX_ITEMS) {
            throw new RestException(overBound(name, distinct.size()));
        }
        ArrayNode ids = RestJson.NODES.arrayNode();
        distinct.forEach(ids::add);
        return ids;
    }

    private static boolean validStatus(JsonNode value, ObjectNode schema, Supplier<Optional<PostSnapshot>> stored) {
        if (value.isTextual()
                && stored.get()
                        .map(post -> post.status().equals(value.textValue()))
                        .orElse(false)) {
            return true;
        }
        Optional<RestError> invalid = SchemaValidator.validate(value, schema, "status");
        if (invalid.isPresent()) {
            throw new RestException(invalid.get());
        }
        return true;
    }

    private static boolean validTemplate(JsonNode value, Supplier<Optional<PostSnapshot>> stored) {
        if (PhpValues.isEmpty(value)) {
            return true;
        }
        String text = PhpValues.toPhpString(value);
        if (stored.get().map(post -> PageTemplates.of(post).equals(text)).orElse(false)) {
            return true;
        }
        // no theme offers templates yet, so no other value is one of them
        throw new RestException(RestError.noStatus("rest_invalid_param", "template is not one of ."));
    }

    private static Arg write(String name, ObjectNode schema, Optional<UnaryOperator<JsonNode>> sanitize) {
        return new Arg(name, schema, false, Optional.empty(), Arg.Phase.VALIDATE, sanitize);
    }

    /** The item GET's arguments in WordPress's order: context, excerpt_length, password. */
    static List<Arg> itemArgs(PostRoutes routes) {
        return List.of(Paging.contextArg(routes.schema()), excerptLengthArg(), passwordArg());
    }

    /** The item route's {@code id}: an integer, checked while the arguments are sanitized. */
    static Arg idArg() {
        ObjectNode id = RestJson.NODES.objectNode();
        id.put("description", ID);
        id.put("type", "integer");
        return new Arg("id", id, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** The delete endpoint's {@code force}: a boolean, default false. */
    static Arg forceArg() {
        ObjectNode force = RestJson.NODES.objectNode();
        force.put("type", "boolean");
        force.put("default", false);
        force.put("description", "Whether to bypass Trash and force deletion.");
        return new Arg(
                "force",
                force,
                false,
                Optional.of(RestJson.NODES.booleanNode(false)),
                Arg.Phase.SANITIZE,
                Optional.empty());
    }

    /** The item GET's {@code password}. */
    static Arg passwordArg() {
        ObjectNode password = RestJson.NODES.objectNode();
        password.put("description", "The password for the post if it is password protected.");
        password.put("type", "string");
        return query("password", password);
    }

    /** The item GET's {@code excerpt_length}. */
    static Arg excerptLengthArg() {
        ObjectNode length = RestJson.NODES.objectNode();
        length.put("description", "Override the default excerpt length.");
        length.put("type", "integer");
        return query("excerpt_length", length);
    }

    private static ObjectNode property(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        return property;
    }

    private static ObjectNode property(
            ObjectNode properties, String name, String description, String type, List<String> contexts) {
        ObjectNode property = property(properties, name, description);
        property.put("type", type);
        contexts(property, contexts);
        return property;
    }

    private static void dateProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = property(properties, name, description);
        property.put("type", "string");
        property.put("format", "date-time");
        contexts(property, VIEW_EDIT);
        property.put("readonly", true);
    }

    private static void contexts(ObjectNode property, List<String> contexts) {
        ArrayNode list = property.putArray("context");
        contexts.forEach(list::add);
    }

    private static Arg dateArg(String name, String description) {
        ObjectNode date = RestJson.NODES.objectNode();
        date.put("description", description);
        date.put("type", "string");
        date.put("format", "date-time");
        return query(name, date);
    }

    private static Arg idList(String name, String description) {
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("description", description);
        list.put("type", "array");
        list.putObject("items").put("type", "integer");
        list.putArray("default");
        return query(name, list);
    }

    private static Arg query(String name, ObjectNode schema) {
        return new Arg(
                name,
                schema,
                false,
                Optional.ofNullable(schema.get("default")).map(JsonNode::deepCopy),
                Arg.Phase.SANITIZE,
                Optional.empty());
    }
}
