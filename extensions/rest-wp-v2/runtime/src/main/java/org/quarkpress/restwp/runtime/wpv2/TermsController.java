package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.PostTermsAccess;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermOrder;
import org.quarkpress.taxonomy.TermPermissions;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * WordPress's term routes for every taxonomy shown in REST, in registration order: {@code /wp/v2/<base>} (list,
 * create) and {@code /wp/v2/<base>/<id>} (read, update, delete), where the base is the taxonomy's REST base or its
 * key. Terms are read and written through the taxonomy module for the request's site, so a term of another site or
 * another taxonomy does not exist here. Permissions are WordPress's: anyone reads; the edit context needs the
 * taxonomy's {@code edit_terms} (list) or the term's {@code edit_term} (item); creating needs {@code edit_terms} for a
 * hierarchical taxonomy and {@code assign_terms} for a flat one; updating {@code edit_term}; deleting
 * {@code delete_term}, which no one holds for the taxonomy's default term. Terms cannot be trashed: a delete needs
 * {@code force}.
 *
 * <p>The lists {@code include}, {@code exclude} and {@code slug} are held at {@value #MAX_LIST_ITEMS} entries each
 * (WordPress has no bound): a longer one is refused as WordPress refuses a list over its schema's {@code maxItems}.
 */
@ApplicationScoped
public class TermsController implements RestRouteProvider {

    /** The most entries {@code include}, {@code exclude} or {@code slug} may list. */
    static final int MAX_LIST_ITEMS = ListArgs.MAX_ITEMS;

    private static final String NAMESPACE = "wp/v2";

    private static final String POST_TYPE_RELATION = "https://api.w.org/post_type";

    @Inject
    Taxonomies taxonomies;

    @Inject
    Terms terms;

    @Inject
    TermPermissions permissions;

    @Inject
    PostTypes types;

    @Inject
    RestUrls urls;

    @Inject
    SiteContext site;

    @Inject
    SlugSanitizer slugs;

    /** One REST taxonomy's routes: its definition, base and item schema (held once: selections are kept per schema). */
    private record Routes(TaxonomyDefinition taxonomy, String base, ObjectNode schema) {

        String key() {
            return taxonomy.key();
        }

        boolean hierarchical() {
            return taxonomy.hierarchical();
        }

        String collection() {
            return "/" + NAMESPACE + "/" + base;
        }

        String item(TermId id) {
            return collection() + "/" + id.value();
        }
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public void register(RouteRegistry routes) {
        for (TaxonomyDefinition taxonomy : taxonomies.all()) {
            if (!taxonomy.showInRest()) {
                continue;
            }
            String base = taxonomy.restBase().filter(rest -> !rest.isEmpty()).orElse(taxonomy.key());
            Routes term = new Routes(taxonomy, base, TermSchema.of(taxonomy));
            register(routes, term);
        }
    }

    private void register(RouteRegistry routes, Routes term) {
        ObjectNode schema = term.schema();
        routes.route(NAMESPACE, "/" + term.base())
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .args(TermSchema.collectionArgs(term.taxonomy(), schema))
                        .permission(request -> mayList(term, request))
                        .handler(request -> list(term, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(TermSchema.writeArgs(term.taxonomy(), true, value -> slug(term, value)))
                        .permission(request -> mayCreate(term, request))
                        .handler(request -> create(term, request))
                        .allowBatch(true)
                        .build())
                .register();
        routes.route(NAMESPACE, "/" + term.base() + "/(?P<id>[\\d]+)")
                .arg(TermSchema.idArg())
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .permission(request -> mayRead(term, request))
                        .handler(request -> item(term, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(TermSchema.writeArgs(term.taxonomy(), false, value -> slug(term, value)))
                        .permission(request -> mayUpdate(term, request))
                        .handler(request -> update(term, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(TermSchema.forceArg())
                        .permission(request -> mayDelete(term, request))
                        .handler(request -> delete(term, request))
                        .allowBatch(true)
                        .build())
                .register();
    }

    // ---- permissions

    private PermissionResult mayList(Routes term, RestRequest request) {
        if (request.isEditContext() && !permissions.canEditTerms(request.identity(), term.key())) {
            return PermissionResult.denied(RestError.of(
                    "rest_forbidden_context",
                    "Sorry, you are not allowed to edit terms in this taxonomy.",
                    request.authCode()));
        }
        Optional<JsonNode> post = request.param("post");
        if (post.isPresent() && !PhpValues.isEmpty(post.get())) {
            long id = PhpValues.toLong(post.get());
            PostTermsAccess access = id > 0
                    ? permissions.postTermsAccess(request.identity(), PostId.of(id), term.key())
                    : PostTermsAccess.MISSING_POST;
            if (access == PostTermsAccess.MISSING_POST) {
                return PermissionResult.denied(RestError.of("rest_post_invalid_id", "Invalid post ID.", 400));
            }
            if (access == PostTermsAccess.DENIED) {
                return PermissionResult.denied(RestError.of(
                        "rest_forbidden_context",
                        "Sorry, you are not allowed to view terms for this post.",
                        request.authCode()));
            }
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayRead(Routes term, RestRequest request) {
        Optional<TermSnapshot> found = find(term, request);
        if (found.isEmpty()) {
            return PermissionResult.denied(missing());
        }
        if (request.isEditContext()
                && !permissions.canEdit(request.identity(), found.get().id())) {
            return PermissionResult.denied(RestError.of(
                    "rest_forbidden_context", "Sorry, you are not allowed to edit this term.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayCreate(Routes term, RestRequest request) {
        if (!permissions.canCreate(request.identity(), term.key())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_create",
                    "Sorry, you are not allowed to create terms in this taxonomy.",
                    request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayUpdate(Routes term, RestRequest request) {
        Optional<TermSnapshot> found = find(term, request);
        if (found.isEmpty()) {
            return PermissionResult.denied(missing());
        }
        if (!permissions.canEdit(request.identity(), found.get().id())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_update", "Sorry, you are not allowed to edit this term.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayDelete(Routes term, RestRequest request) {
        Optional<TermSnapshot> found = find(term, request);
        if (found.isEmpty()) {
            return PermissionResult.denied(missing());
        }
        if (!permissions.canDelete(request.identity(), found.get().id())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_delete", "Sorry, you are not allowed to delete this term.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    // ---- handlers

    private RestResponse list(Routes term, RestRequest request) {
        Paging paging = Paging.of(request);
        Optional<TermQuery.Builder> built = query(term, request);
        if (built.isEmpty()) {
            // an include list of only zeros: WordPress's IN (0) matches nothing, and counts nothing
            return RestResponse.paged(RestJson.NODES.arrayNode(), 0, paging, request);
        }
        TermQuery.Builder query = built.get();
        long total = terms.count(query.build());
        if (request.method().equals("HEAD")) {
            // no body is sent, so no term is read
            return RestResponse.paged(RestJson.NODES.arrayNode(), total, paging, request);
        }
        List<TermSnapshot> page = terms.query(
                query.number(paging.perPage()).offset(paging.offset()).build());
        boolean edit = request.isEditContext();
        Fields.Selection selection = Fields.forResponse(request, term.schema());
        ArrayNode items = RestJson.NODES.arrayNode();
        for (TermSnapshot found : page) {
            if (edit && !permissions.canEdit(request.identity(), found.id())) {
                // dropped after paging, so the totals still count it
                continue;
            }
            items.add(CollectionItems.inline(prepare(term, found, selection, request.context()), request));
        }
        return RestResponse.paged(items, total, paging, request);
    }

    private RestResponse item(Routes term, RestRequest request) {
        TermSnapshot found = find(term, request).orElseThrow(() -> new RestException(missing()));
        return prepare(term, found, Fields.forResponse(request, term.schema()), request.context());
    }

    private RestResponse create(Routes term, RestRequest request) {
        requireValidParent(term, request);
        TermSnapshot created = terms.create(term.key(), changes(term, request).build());
        return prepare(term, created, editSelection(term, request), "edit")
                .withStatus(201)
                .withHeader("Location", urls.route(term.item(created.id())));
    }

    private RestResponse update(Routes term, RestRequest request) {
        TermSnapshot found = find(term, request).orElseThrow(() -> new RestException(missing()));
        requireValidParent(term, request);
        TermChanges changes = changes(term, request).build();
        if (changes.name().isPresent()
                || changes.slug().isPresent()
                || changes.description().isPresent()
                || changes.parent().isPresent()) {
            terms.update(found.id(), term.key(), changes);
        }
        TermSnapshot updated = terms.find(found.id(), term.key()).orElseThrow(() -> new RestException(missing()));
        return prepare(term, updated, editSelection(term, request), "edit");
    }

    private RestResponse delete(Routes term, RestRequest request) {
        TermSnapshot found = find(term, request).orElseThrow(() -> new RestException(missing()));
        boolean force = request.param("force").map(PhpValues::toBoolean).orElse(false);
        if (!force) {
            throw new RestException(RestError.of(
                    "rest_trash_not_supported", "Terms do not support trashing. Set 'force=true' to delete.", 501));
        }
        Fields.Selection selection = Fields.forResponse(Fields.of(request), term.schema(), "view", false);
        JsonNode previous = prepare(term, found, selection, "view").body();
        if (terms.delete(found.id(), term.key()).isEmpty()) {
            throw new RestException(RestError.of("rest_cannot_delete", "The term cannot be deleted.", 500));
        }
        ObjectNode body = RestJson.NODES.objectNode();
        body.set("deleted", BooleanNode.TRUE);
        body.set("previous", previous);
        return RestResponse.ok(body);
    }

    // ---- helpers

    /** The term the request's {@code id} names in this taxonomy on the request's site; empty for an id below 1. */
    private Optional<TermSnapshot> find(Routes term, RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        return id > 0 ? terms.find(TermId.of(id), term.key()) : Optional.empty();
    }

    private static RestError missing() {
        return RestError.of("rest_term_invalid", "Term does not exist.", 404);
    }

    /**
     * WordPress's checks of a requested parent before a write: any {@code parent} (declared or not) is refused for a
     * flat taxonomy; a parent other than 0 must be a term of this taxonomy on this site.
     */
    private void requireValidParent(Routes term, RestRequest request) {
        Optional<JsonNode> parent = request.param("parent");
        if (parent.isEmpty()) {
            return;
        }
        if (!term.hierarchical()) {
            throw new RestException(RestError.of(
                    "rest_taxonomy_not_hierarchical", "Cannot set parent term, taxonomy is not hierarchical.", 400));
        }
        long id = PhpValues.toLong(parent.get());
        if (id != 0 && (id < 0 || terms.find(TermId.of(id), term.key()).isEmpty())) {
            throw new RestException(RestError.of("rest_term_invalid", "Parent term does not exist.", 400));
        }
    }

    /** The fields the request gives: name, slug, description and, in a hierarchical taxonomy, the parent. */
    private TermChanges.Builder changes(Routes term, RestRequest request) {
        TermChanges.Builder changes = TermChanges.builder();
        request.param("name").ifPresent(value -> changes.name(TermSchema.scalar(value)));
        request.param("slug").ifPresent(value -> changes.slug(TermSchema.scalar(value)));
        request.param("description").ifPresent(value -> changes.description(TermSchema.scalar(value)));
        if (term.hierarchical()) {
            request.param("parent").ifPresent(value -> changes.parent(Math.max(0, PhpValues.toLong(value))));
        }
        return changes;
    }

    /** The fields of a written term's answer: WordPress answers in the edit context whatever context was asked. */
    private static Fields.Selection editSelection(Routes term, RestRequest request) {
        return Fields.forResponse(Fields.of(request), term.schema(), "edit", request.hasParam("_embed"));
    }

    /**
     * The term query of the collection's arguments, without paging; empty when it can match nothing (an include list
     * of only zeros).
     */
    private Optional<TermQuery.Builder> query(Routes term, RestRequest request) {
        TermQuery.Builder query = TermQuery.builder().taxonomies(term.key());
        List<Long> include = absoluteIds(ListArgs.bounded(request, "include"));
        if (!include.isEmpty()) {
            List<TermId> positive = positive(include);
            if (positive.isEmpty()) {
                return Optional.empty();
            }
            query.include(positive.toArray(TermId[]::new));
        }
        query.exclude(
                positive(absoluteIds(ListArgs.bounded(request, "exclude"))).toArray(TermId[]::new));
        List<String> slugList = texts(ListArgs.bounded(request, "slug"));
        if (!slugList.isEmpty()) {
            query.slugs(slugList.toArray(String[]::new));
        }
        request.param("order").ifPresent(order -> query.order(TermSchema.scalar(order)));
        request.param("orderby").ifPresent(orderby -> query.orderBy(orderBy(TermSchema.scalar(orderby))));
        request.param("hide_empty").ifPresent(hide -> query.hideEmpty(PhpValues.toBoolean(hide)));
        request.param("search").ifPresent(search -> query.search(TermSchema.scalar(search)));
        if (term.hierarchical()) {
            request.param("parent").ifPresent(parent -> {
                long id = PhpValues.toLong(parent);
                if (id != 0 || parent.isIntegralNumber()) {
                    query.parent(id);
                }
            });
        }
        Optional<JsonNode> post = request.param("post");
        if (post.isPresent() && PhpValues.toLong(post.get()) > 0) {
            query.posts(PostId.of(PhpValues.toLong(post.get())));
        }
        return Optional.of(query);
    }

    /** WordPress's REST orderby names: id is the term id and include_slugs the slug list's order. */
    private static TermOrder orderBy(String orderby) {
        return switch (orderby) {
            case "include_slugs" -> TermOrder.SLUG_IN;
            default -> TermOrder.parse(orderby);
        };
    }

    /** WordPress's id lists: each item's absolute integer value, each once, in order. */
    private static List<Long> absoluteIds(List<JsonNode> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : items) {
            long id = PhpValues.toLong(item);
            ids.add(id == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(id));
        }
        return List.copyOf(ids);
    }

    private static List<TermId> positive(List<Long> ids) {
        List<TermId> positive = new ArrayList<>(ids.size());
        for (long id : ids) {
            if (id > 0) {
                positive.add(TermId.of(id));
            }
        }
        return positive;
    }

    private static List<String> texts(List<JsonNode> items) {
        List<String> texts = new ArrayList<>(items.size());
        items.forEach(item -> texts.add(TermSchema.scalar(item)));
        return texts;
    }

    /** WordPress's slug sanitizing of a requested slug, through the site's slug sanitizer. */
    private JsonNode slug(Routes term, JsonNode value) {
        String text = TermSchema.scalar(value);
        return TextNode.valueOf(slugs.sanitize(
                text, new SlugContext(site.siteId(), Optional.empty(), term.key(), "", Optional.empty(), text)));
    }

    /**
     * The term's selected fields in WordPress's order, filtered by {@code context}; its links when they are selected.
     * {@code meta} is always empty: no term meta is shown in REST.
     */
    private RestResponse prepare(Routes term, TermSnapshot found, Fields.Selection fields, String context) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.includesExactly("id")) {
            data.put("id", found.id().value());
        }
        if (fields.includesExactly("count")) {
            data.put("count", found.count());
        }
        if (fields.includesExactly("description")) {
            data.put("description", found.description());
        }
        if (fields.includesExactly("link")) {
            data.put("link", link(term.taxonomy(), found));
        }
        if (fields.includesExactly("name")) {
            data.put("name", found.name());
        }
        if (fields.includesExactly("slug")) {
            data.put("slug", found.slug());
        }
        if (fields.includesExactly("taxonomy")) {
            data.put("taxonomy", found.taxonomy());
        }
        if (term.hierarchical() && fields.includesExactly("parent")) {
            data.put("parent", found.parent().map(TermId::value).orElse(0L));
        }
        if (fields.includesExactly("meta")) {
            data.putArray("meta");
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, term.schema(), context));
        if (fields.includes("_links") || fields.includes("_embedded")) {
            response = response.withLinks(links(term, found));
        }
        return response;
    }

    /**
     * The term's archive link in plain permalinks (pretty permalinks come with M3): {@code ?cat=<id>} for a category,
     * {@code ?<query variable>=<slug>} for a taxonomy that has a query variable, else
     * {@code ?taxonomy=<taxonomy>&term=<slug>}; the slug is the stored one, already percent-encoded. A taxonomy that is
     * not publicly queryable has no query variable; a publicly queryable one has WordPress's default, its key
     * ({@code tag} for tags), since QuarkPress registrations name no other.
     */
    private String link(TaxonomyDefinition taxonomy, TermSnapshot found) {
        String home = urls.home();
        return switch (taxonomy.key()) {
            case "category" -> home + "?cat=" + found.id().value();
            case "post_tag" -> home + "?tag=" + found.slug();
            default ->
                taxonomy.publiclyQueryable()
                        ? home + "?" + taxonomy.key() + "=" + found.slug()
                        : home + "?taxonomy=" + taxonomy.key() + "&term=" + found.slug();
        };
    }

    /**
     * self, collection, about (the taxonomy), up (the parent, embeddable, when it exists) and one
     * {@code https://api.w.org/post_type} link per post type of the taxonomy shown in REST, to that type's collection
     * of posts with this term.
     */
    private Links links(Routes term, TermSnapshot found) {
        Links links = Links.none()
                .add("self", urls.route(term.item(found.id())))
                .add("collection", urls.route(term.collection()))
                .add("about", urls.route("/" + NAMESPACE + "/taxonomies/" + term.key()));
        if (found.parent().isPresent()
                && terms.find(found.parent().get(), term.key()).isPresent()) {
            links = links.add(
                    "up", urls.route(term.item(found.parent().get())), Map.of("embeddable", BooleanNode.TRUE));
        }
        for (String type : term.taxonomy().objectTypes()) {
            Optional<PostTypeDefinition> definition = types.find(type);
            if (definition.isEmpty() || !definition.get().showInRest()) {
                continue;
            }
            String base =
                    definition.get().restBase().filter(rest -> !rest.isEmpty()).orElse(type);
            links = links.add(
                    POST_TYPE_RELATION,
                    urls.route(
                            "/" + TypeExtras.of(type).restNamespace() + "/" + base,
                            Map.of(term.base(), Long.toString(found.id().value()))));
        }
        return links;
    }
}
