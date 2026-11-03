package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.core.TitleFilter;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.Slugs;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * WordPress's {@code GET /wp/v2/search}, public, with its three handlers chosen by {@code type}: {@code post} (the
 * default) lists the published posts of the public post types shown in REST but attachments, for every caller alike,
 * ranked by relevance then date for a search and newest first without one; {@code term} lists the terms of the public
 * taxonomies shown in REST, empty ones included, by name; {@code post-format} lists the formats whose term exists on
 * the site, in WordPress's fixed order, a search matching the slug or the label. {@code subtype} narrows a handler to
 * its own subtypes: {@code any} means all of them, and a list that names none of them falls back to the type
 * {@code post} alone for posts and to every taxonomy for terms. The post-format handler ignores {@code subtype},
 * {@code include} and {@code exclude}.
 *
 * <p>An item is its id (a string for a format), the title, the plain front-end link, the type and, for a post, the
 * subtype; a term's type is its taxonomy. A page without posts is an empty page with zero totals and a link to the
 * page before it; the term and format handlers count their results without paging, so a page past their end is
 * refused with {@code rest_search_invalid_page_number}.
 */
@ApplicationScoped
public class SearchController implements RestRouteProvider {

    private static final String NAMESPACE = "wp/v2";

    private static final String ROUTE = "/search";

    private static final String TYPE_POST = "post";

    private static final String TYPE_TERM = "term";

    private static final String TYPE_FORMAT = "post-format";

    private static final String ANY = "any";

    /** The post type whose routes another controller serves and which the post handler never lists. */
    private static final String ATTACHMENT = "attachment";

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostStatuses statuses;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    PostFormats formats;

    @Inject
    PostFields fields;

    @Inject
    MetaFields meta;

    @Inject
    RestUrls urls;

    @Inject
    @All
    List<InstanceHandle<TitleFilter>> titleHandles;

    private List<TitleFilter> titleFilters;

    /**
     * The routes of the post types the post handler lists, by type key, and the taxonomies the term handler lists,
     * by key, each in registration order. {@link #register} is the only writer: it swaps in new immutable maps each
     * time the route table is built, so requests read a consistent snapshot.
     */
    private volatile Map<String, PostRoutes> postTypes = Map.of();

    private volatile Map<String, TaxonomyDefinition> termTaxonomies = Map.of();

    private volatile ObjectNode schema = RestJson.NODES.objectNode();

    private record Rendering(SiteId site) implements RenderContext {}

    /** One handler's answer: the prepared items of the page and the total the headers report. */
    private record Page(List<RestResponse> items, long total) {}

    @PostConstruct
    void orderFilters() {
        titleFilters = Filters.ordered(titleHandles);
    }

    @Override
    public int order() {
        return 550;
    }

    @Override
    public void register(RouteRegistry routes) {
        List<String> creatable = PostRoutes.creatable(statuses.all());
        Map<String, PostRoutes> servedTypes = new LinkedHashMap<>();
        for (PostTypeDefinition type : types.all()) {
            if (!type.publicly() || !type.showInRest() || type.key().equals(ATTACHMENT)) {
                continue;
            }
            servedTypes.put(type.key(), PostRoutes.of(type, taxonomies.all(), meta, creatable));
        }
        Map<String, TaxonomyDefinition> servedTaxonomies = new LinkedHashMap<>();
        for (TaxonomyDefinition taxonomy : taxonomies.all()) {
            if (taxonomy.publicly() && taxonomy.showInRest()) {
                servedTaxonomies.put(taxonomy.key(), taxonomy);
            }
        }
        List<String> subtypes = new ArrayList<>(servedTypes.keySet());
        subtypes.addAll(servedTaxonomies.keySet());
        ObjectNode itemSchema = schema(subtypes);
        postTypes = Collections.unmodifiableMap(servedTypes);
        termTaxonomies = Collections.unmodifiableMap(servedTaxonomies);
        schema = itemSchema;
        routes.route(NAMESPACE, ROUTE)
                .schema(() -> itemSchema)
                .endpoint(Endpoint.of("GET")
                        .args(args(itemSchema, subtypes))
                        .handler(this::list)
                        .build())
                .register();
    }

    // ---- the handler

    private RestResponse list(RestRequest request) {
        Paging paging = Paging.of(request);
        String type = ListArgs.scalar(request.param("type").orElse(null));
        Page page = switch (type) {
            case TYPE_TERM -> terms(request, paging);
            case TYPE_FORMAT -> postFormats(request, paging);
            default -> posts(request, paging);
        };
        ArrayNode items = RestJson.NODES.arrayNode();
        if (!request.method().equals("HEAD")) {
            for (RestResponse item : page.items()) {
                items.add(CollectionItems.inline(item, request));
            }
        }
        long total = page.total();
        if (total > 0) {
            if (paging.page() > (total - 1) / paging.perPage() + 1) {
                // the term and format handlers count without paging; the post handler counts no post for an empty
                // window, so a page past its end is the empty page below
                throw new RestException(RestError.of(
                        "rest_search_invalid_page_number",
                        "The page number requested is larger than the number of pages available.",
                        400));
            }
            return RestResponse.paged(items, total, paging, request);
        }
        // no result in the window: zero totals and a link back to the page before it, unclamped (the links of a
        // collection whose last page is that one)
        return RestResponse.paged(items, (paging.page() - 1L) * paging.perPage(), paging, request)
                .withHeader("X-WP-Total", "0")
                .withHeader("X-WP-TotalPages", "0");
    }

    // ---- the post handler

    /**
     * The published posts of the requested subtypes (the type {@code post} alone when none of the handler's is
     * named), newest first, or by relevance then date for a search; {@code include} wins over {@code exclude}.
     */
    private Page posts(RestRequest request, Paging paging) {
        List<String> subtypes = subtypes(request, new ArrayList<>(postTypes.keySet()));
        if (subtypes.isEmpty()) {
            subtypes = List.of(TYPE_POST);
        }
        PostQuery.Builder query = PostQuery.builder()
                .types(subtypes.toArray(String[]::new))
                .statuses("publish")
                .perPage(paging.perPage())
                .page(paging.page());
        Optional<String> search = search(request);
        if (search.isPresent()) {
            query.search(search.get()).orderBy(OrderKey.RELEVANCE, false).orderBy(OrderKey.DATE, false);
        } else {
            query.orderBy(OrderKey.DATE, false);
        }
        List<Long> include = ListArgs.absoluteIds(ListArgs.bounded(request, "include"));
        if (!include.isEmpty()) {
            List<PostId> postIn = ListArgs.positive(include, PostId::of);
            if (postIn.isEmpty()) {
                // an include list of only zeros matches nothing
                return new Page(List.of(), 0);
            }
            query.postIn(postIn.toArray(PostId[]::new));
        } else {
            query.postNotIn(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "exclude")), PostId::of)
                    .toArray(PostId[]::new));
        }
        PostPage found = posts.query(query.build());
        Fields.Selection selection = Fields.forResponse(request, schema);
        List<RestResponse> items = new ArrayList<>();
        for (PostSnapshot post : found.posts()) {
            PostRoutes routes = postTypes.get(post.type());
            if (routes == null) {
                continue;
            }
            items.add(post(routes, post, selection, request.context()));
        }
        return new Page(items, found.posts().isEmpty() ? 0 : found.found());
    }

    /** A post's item: id, the rendered title (empty for a type without one), its plain link, the type and subtype. */
    private RestResponse post(PostRoutes routes, PostSnapshot post, Fields.Selection selection, String context) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (selection.includesExactly("id")) {
            data.put("id", post.id().value());
        }
        if (selection.includesExactly("title")) {
            Rendering rendering = new Rendering(post.site());
            data.put(
                    "title",
                    routes.supports("title")
                            ? Filters.apply(
                                    titleFilters, post.title(), (filter, value) -> filter.apply(value, rendering))
                            : "");
        }
        if (selection.includesExactly("url")) {
            data.put("url", fields.link(routes.type(), post));
        }
        if (selection.includesExactly("type")) {
            data.put("type", TYPE_POST);
        }
        if (selection.includesExactly("subtype")) {
            data.put("subtype", routes.key());
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, schema, context));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            response = response.withLinks(Links.none()
                    .add("self", urls.route(routes.item(post.id())), Map.of("embeddable", BooleanNode.TRUE))
                    .add("about", urls.route("/" + NAMESPACE + "/types/" + routes.key()))
                    .add("collection", urls.route("/" + NAMESPACE + ROUTE)));
        }
        return response;
    }

    // ---- the term handler

    /**
     * The terms of the requested taxonomies (every one of the handler's when none is named), empty ones included,
     * by name; the total counts the matching terms without paging.
     */
    private Page terms(RestRequest request, Paging paging) {
        List<String> keys = subtypes(request, new ArrayList<>(termTaxonomies.keySet()));
        if (keys.isEmpty()) {
            keys = new ArrayList<>(termTaxonomies.keySet());
        }
        TermQuery.Builder query =
                TermQuery.builder().taxonomies(keys.toArray(String[]::new)).hideEmpty(false);
        search(request).ifPresent(query::search);
        List<Long> include = ListArgs.absoluteIds(ListArgs.bounded(request, "include"));
        if (!include.isEmpty()) {
            List<TermId> positive = ListArgs.positive(include, TermId::of);
            if (positive.isEmpty()) {
                return new Page(List.of(), 0);
            }
            query.include(positive.toArray(TermId[]::new));
        }
        query.exclude(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "exclude")), TermId::of)
                .toArray(TermId[]::new));
        long total = terms.count(query.build());
        List<TermSnapshot> found = terms.query(
                query.number(paging.perPage()).offset(paging.offset()).build());
        Fields.Selection selection = Fields.forResponse(request, schema);
        List<RestResponse> items = new ArrayList<>();
        for (TermSnapshot term : found) {
            TaxonomyDefinition taxonomy = termTaxonomies.get(term.taxonomy());
            if (taxonomy == null) {
                continue;
            }
            items.add(term(taxonomy, term, selection, request.context()));
        }
        return new Page(items, total);
    }

    /** A term's item: id, its name as the title, its plain link and the taxonomy as the type; no subtype. */
    private RestResponse term(
            TaxonomyDefinition taxonomy, TermSnapshot term, Fields.Selection selection, String context) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (selection.includesExactly("id")) {
            data.put("id", term.id().value());
        }
        if (selection.includesExactly("title")) {
            data.put("title", term.name());
        }
        if (selection.includesExactly("url")) {
            data.put("url", TermsController.link(taxonomy, term, urls));
        }
        if (selection.includesExactly("type")) {
            data.put("type", taxonomy.key());
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, schema, context));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            String base = PostRoutes.base(taxonomy);
            response = response.withLinks(Links.none()
                    .add(
                            "self",
                            urls.route("/" + NAMESPACE + "/" + base + "/"
                                    + term.id().value()),
                            Map.of("embeddable", BooleanNode.TRUE))
                    .add("about", urls.route("/" + NAMESPACE + "/taxonomies/" + taxonomy.key()))
                    .add("collection", urls.route("/" + NAMESPACE + ROUTE)));
        }
        return response;
    }

    // ---- the post-format handler

    /**
     * The formats whose term exists on the site, in WordPress's order; a search keeps those whose slug or label
     * holds it, whatever the case. The page is cut from that list; the total is its length.
     */
    private Page postFormats(RestRequest request, Paging paging) {
        String needle =
                search(request).map(text -> text.toLowerCase(Locale.ROOT)).orElse("");
        List<String> matched = new ArrayList<>();
        for (String slug : PostFormats.SLUGS) {
            String label = PostFormats.LABELS.get(slug);
            boolean matches = needle.isEmpty()
                    || slug.toLowerCase(Locale.ROOT).contains(needle)
                    || label.toLowerCase(Locale.ROOT).contains(needle);
            if (matches && formats.exists(slug)) {
                matched.add(slug);
            }
        }
        Fields.Selection selection = Fields.forResponse(request, schema);
        List<RestResponse> items = new ArrayList<>();
        int from = Math.min(paging.offset(), matched.size());
        int to = (int) Math.min(matched.size(), from + (long) paging.perPage());
        for (String slug : matched.subList(from, to)) {
            items.add(postFormat(slug, selection, request.context()));
        }
        return new Page(items, matched.size());
    }

    /** A format's item: the slug as a string id, the label, the format archive's plain link; a collection link only. */
    private RestResponse postFormat(String slug, Fields.Selection selection, String context) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (selection.includesExactly("id")) {
            data.put("id", slug);
        }
        if (selection.includesExactly("title")) {
            data.put("title", PostFormats.LABELS.get(slug));
        }
        if (selection.includesExactly("url")) {
            data.put("url", urls.home() + "?post_format=" + slug);
        }
        if (selection.includesExactly("type")) {
            data.put("type", TYPE_FORMAT);
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, schema, context));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            response = response.withLinks(Links.none().add("collection", urls.route("/" + NAMESPACE + ROUTE)));
        }
        return response;
    }

    // ---- the arguments

    /** The search text when it is not empty. */
    private static Optional<String> search(RestRequest request) {
        return request.param("search").map(ListArgs::scalar).filter(text -> !text.isEmpty());
    }

    /**
     * The handler's subtypes the request names, in the request's order: all of them without the parameter or for
     * {@code any} anywhere, else the named ones that are the handler's; the others are dropped without a word, so
     * the list may be empty (an empty {@code subtype=} names none).
     */
    private static List<String> subtypes(RestRequest request, List<String> handlers) {
        if (request.param("subtype").isEmpty()) {
            return handlers;
        }
        List<String> named = ListArgs.texts(ListArgs.bounded(request, "subtype"));
        if (named.contains(ANY)) {
            return handlers;
        }
        List<String> kept = new ArrayList<>();
        for (String subtype : named) {
            if (handlers.contains(subtype) && !kept.contains(subtype)) {
                kept.add(subtype);
            }
        }
        return kept;
    }

    /**
     * WordPress's arguments in its order: the common collection parameters (the context enum is the item schema's,
     * view and embed), {@code type}, {@code subtype} (a slug list, {@code any} anywhere meaning {@code any} alone),
     * {@code exclude} and {@code include}.
     */
    private static List<Arg> args(ObjectNode itemSchema, List<String> subtypes) {
        List<Arg> args = new ArrayList<>(Paging.collectionArgs(itemSchema, false));
        ObjectNode type = RestJson.NODES.objectNode();
        type.put("default", TYPE_POST);
        type.put("description", "Limit results to items of an object type.");
        type.put("type", "string");
        ArrayNode types = type.putArray("enum");
        types.add(TYPE_POST);
        types.add(TYPE_TERM);
        types.add(TYPE_FORMAT);
        args.add(new Arg(
                "type",
                type,
                false,
                Optional.of(TextNode.valueOf(TYPE_POST)),
                Arg.Phase.VALIDATE,
                Optional.of(value -> TextNode.valueOf(ListArgs.scalar(value)))));
        ObjectNode subtype = RestJson.NODES.objectNode();
        subtype.put("default", ANY);
        subtype.put("description", "Limit results to items of one or more object subtypes.");
        subtype.put("type", "array");
        ObjectNode items = subtype.putObject("items");
        ArrayNode values = items.putArray("enum");
        subtypes.forEach(values::add);
        values.add(ANY);
        items.put("type", "string");
        args.add(new Arg(
                "subtype",
                subtype,
                false,
                Optional.of(TextNode.valueOf(ANY)),
                Arg.Phase.VALIDATE,
                Optional.of(SearchController::subtypeList)));
        args.add(idList("exclude", "Ensure result set excludes specific IDs."));
        args.add(idList("include", "Limit result set to specific IDs."));
        return List.copyOf(args);
    }

    /** The slug list of {@code subtype}; {@code any} anywhere in it is the whole list. */
    private static JsonNode subtypeList(JsonNode value) {
        ArrayNode list = RestJson.NODES.arrayNode();
        List<String> slugs = ListArgs.slugList(value, Slugs::sanitizeTitle);
        if (slugs.contains(ANY)) {
            list.add(ANY);
            return list;
        }
        slugs.forEach(list::add);
        return list;
    }

    private static Arg idList(String name, String description) {
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("description", description);
        list.put("type", "array");
        list.putObject("items").put("type", "integer");
        list.putArray("default");
        return new Arg(
                name, list, false, Optional.of(RestJson.NODES.arrayNode()), Arg.Phase.SANITIZE, Optional.empty());
    }

    // ---- the schema

    /** WordPress's {@code search-result} schema: id (integer or string), title, url, type and subtype. */
    private static ObjectNode schema(List<String> subtypes) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", "search-result");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode id = property(properties, "id", "Unique identifier for the object.");
        ArrayNode idTypes = id.putArray("type");
        idTypes.add("integer");
        idTypes.add("string");
        contexts(id);
        ObjectNode title = property(properties, "title", "The title for the object.");
        title.put("type", "string");
        contexts(title);
        ObjectNode url = property(properties, "url", "URL to the object.");
        url.put("type", "string");
        url.put("format", "uri");
        contexts(url);
        ObjectNode type = property(properties, "type", "Object type.");
        type.put("type", "string");
        ArrayNode types = type.putArray("enum");
        types.add(TYPE_POST);
        types.add(TYPE_TERM);
        types.add(TYPE_FORMAT);
        contexts(type);
        ObjectNode subtype = property(properties, "subtype", "Object subtype.");
        subtype.put("type", "string");
        ArrayNode values = subtype.putArray("enum");
        subtypes.forEach(values::add);
        contexts(subtype);
        return schema;
    }

    private static ObjectNode property(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        return property;
    }

    /** The property's contexts (view and embed) and its read-only flag, after its type. */
    private static void contexts(ObjectNode property) {
        ArrayNode contexts = property.putArray("context");
        contexts.add("view");
        contexts.add("embed");
        property.put("readonly", true);
    }
}
