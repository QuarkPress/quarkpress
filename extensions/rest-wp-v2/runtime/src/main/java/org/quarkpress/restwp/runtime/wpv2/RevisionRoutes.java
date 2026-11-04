package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.revisions.RevisionOrder;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.revisions.runtime.RevisionEntity;
import org.quarkpress.revisions.runtime.RevisionRepository;

/**
 * WordPress's revisions routes of a post type: {@code /<namespace>/<base>/<parent>/revisions} (the list) and
 * {@code .../revisions/<id>} (one revision; its DELETE is refused for everyone, as WordPress refuses it, so
 * {@code Allow} never lists it). The parent is looked up on the request's site and must be of the route's type, else
 * it does not exist ({@code rest_post_invalid_parent}); reading needs {@code edit_post} on it. The list carries
 * every user's revisions, autosaves included, in the asked order; without {@code per_page} it is unpaged (every row,
 * one page), with it the window starts at {@code offset} when one is given (even 0, the {@code page} then moving
 * only the links) or at the page; an offset at or past the total, or a page past the last, is refused while the post
 * has revisions; a post without revisions, or one whose revisions are off, answers an empty list with zero totals.
 * The totals count what the list would carry, and agree with the post's {@code version-history} count. A bean only
 * while the revisions module is present: the build removes it otherwise, and the posts controller reaches it as a
 * {@link TypeRouteExtension}.
 */
@ApplicationScoped
class RevisionRoutes implements TypeRouteExtension {

    private static final String REVISIONS = "/revisions";

    private static final String PARENT = "The ID for the parent of the revision.";

    private static final String ID = "Unique identifier for the revision.";

    @Inject
    Revisions revisions;

    @Inject
    RevisionRepository revisionRows;

    @Inject
    PostPermissions permissions;

    @Inject
    SiteContext site;

    @Inject
    PostsController.ItemReads reads;

    @Inject
    RevisionFields fields;

    /** Registers the two routes of {@code post}'s type, after the type's own routes. */
    @Override
    public void register(RouteRegistry routes, PostRoutes post) {
        ObjectNode schema = fields.schema(post);
        String collection = "/" + post.base() + "/(?P<parent>[\\d]+)" + REVISIONS;
        routes.route(post.namespace(), collection)
                .arg(integerArg("parent", PARENT))
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .args(listArgs(schema))
                        .permission(request -> mayRead(post, request))
                        .handler(request -> list(post, schema, request))
                        .build())
                .register();
        routes.route(post.namespace(), collection + "/(?P<id>[\\d]+)")
                .arg(integerArg("parent", PARENT))
                .arg(integerArg("id", ID))
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .permission(request -> mayRead(post, request))
                        .handler(request -> item(post, schema, request))
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(forceArg())
                        .permission(request -> mayDelete(post, request))
                        .handler(request -> {
                            throw new RestException(cannotDeleteRevision(request));
                        })
                        .build())
                .register();
    }

    // ---- permissions

    /**
     * The parent exists under the route's type and the caller may edit it: the read check of the revisions routes and
     * of the single autosave read, against the path's {@code parent}.
     */
    PermissionResult mayRead(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> parent = parent(routes, request);
        if (parent.isEmpty()) {
            return PermissionResult.denied(invalidParent());
        }
        if (!permissions.can(request.identity(), "edit_post", parent.get())) {
            return PermissionResult.denied(cannotRead(request));
        }
        return PermissionResult.allowed();
    }

    /**
     * WordPress's delete checks in order: the parent, {@code delete_post} on it, the revision, {@code edit_post} on
     * the parent, then {@code delete_post} on the revision, which no user holds.
     */
    private PermissionResult mayDelete(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> parent = parent(routes, request);
        if (parent.isEmpty()) {
            return PermissionResult.denied(invalidParent());
        }
        if (!permissions.can(request.identity(), "delete_post", parent.get())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_delete",
                    "Sorry, you are not allowed to delete revisions of this post.",
                    request.authCode()));
        }
        if (revision(request).isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        if (!permissions.can(request.identity(), "edit_post", parent.get())) {
            return PermissionResult.denied(cannotRead(request));
        }
        return PermissionResult.denied(cannotDeleteRevision(request));
    }

    // ---- handlers

    private RestResponse list(PostRoutes routes, ObjectNode schema, RestRequest request) {
        String orderby = ListArgs.scalar(request.param("orderby").orElse(null));
        if (orderby.equals("relevance")
                && request.param("search").map(PhpValues::isEmpty).orElse(true)) {
            throw new RestException(RestError.of(
                    "rest_no_search_term_defined", "You need to define a search term to order by relevance.", 400));
        }
        List<Long> include = ListArgs.absoluteIds(ListArgs.bounded(request, "include"));
        if (orderby.equals("include") && include.isEmpty()) {
            throw new RestException(RestError.of(
                    "rest_orderby_include_missing_include",
                    "You need to define an include parameter to order by include.",
                    400));
        }
        PostSnapshot parent = parent(routes, request).orElseThrow(() -> new RestException(invalidParent()));
        int page = (int) Math.max(
                1,
                Math.min(
                        Integer.MAX_VALUE,
                        request.param("page").map(PhpValues::toLong).orElse(1L)));
        Optional<Integer> perPage = request.param("per_page")
                .map(PhpValues::toLong)
                .map(value -> (int) Math.max(1, Math.min(Integer.MAX_VALUE, value)));
        OptionalLong offset = request.param("offset")
                .map(PhpValues::toLong)
                .map(value -> value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value))
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
        List<RevisionId> includeIds = ListArgs.positive(include, RevisionId::of);
        // an include list of nothing but zeros matches no row, and the query could not express it
        boolean nothing = !include.isEmpty() && includeIds.isEmpty();
        RevisionQuery.Builder query = RevisionQuery.builder()
                .ascending(ListArgs.scalar(request.param("order").orElse(null)).equals("asc"))
                .order(order(orderby))
                .include(includeIds.toArray(RevisionId[]::new))
                .exclude(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "exclude")), RevisionId::of)
                        .toArray(RevisionId[]::new));
        request.param("search")
                .map(ListArgs::scalar)
                .filter(text -> !text.isEmpty())
                .ifPresent(query::search);
        long total = nothing ? 0 : revisions.count(parent.id(), query.build());
        long totalPages =
                total <= 0 ? 0 : perPage.map(size -> (total - 1) / size + 1).orElse(1L);
        if (total > 0) {
            if (offset.isPresent() && offset.getAsLong() >= total) {
                throw new RestException(RestError.of(
                        "rest_revision_invalid_offset_number",
                        "The offset number requested is larger than or equal to the number of available revisions.",
                        400));
            }
            if ((offset.isEmpty() || offset.getAsLong() == 0) && page > totalPages) {
                throw new RestException(RestError.of(
                        "rest_revision_invalid_page_number",
                        "The page number requested is larger than the number of pages available.",
                        400));
            }
        }
        ArrayNode items = RestJson.NODES.arrayNode();
        if (total > 0 && !request.method().equals("HEAD")) {
            if (perPage.isPresent()) {
                long start = offset.orElse(((long) page - 1) * perPage.get());
                query.limit(perPage.get()).offset((int) Math.min(Integer.MAX_VALUE, start));
            }
            Fields.Selection selection = Fields.forResponse(request, schema);
            for (RevisionSnapshot revision : revisions.ofPost(parent.id(), query.build())) {
                items.add(CollectionItems.inline(
                        fields.prepare(routes, revision, selection, request.context()), request));
            }
        }
        return RestResponse.pagedAt(items, total, totalPages, page, request);
    }

    private RestResponse item(PostRoutes routes, ObjectNode schema, RestRequest request) {
        PostSnapshot parent = parent(routes, request).orElseThrow(() -> new RestException(invalidParent()));
        RevisionSnapshot revision = revision(request).orElseThrow(() -> new RestException(invalidId()));
        if (!revision.post().equals(parent.id())) {
            throw new RestException(RestError.of(
                    "rest_revision_parent_id_mismatch",
                    "The revision does not belong to the specified parent with id of \""
                            + parent.id().value() + "\"",
                    404));
        }
        return fields.prepare(routes, revision, Fields.forResponse(request, schema), request.context());
    }

    // ---- lookups

    /** The post of this site with the route's {@code parent} and type, shared with the type's item reads. */
    private Optional<PostSnapshot> parent(PostRoutes routes, RestRequest request) {
        return reads.post(routes, request, "parent");
    }

    /** The revision of this site with the route's {@code id}, autosaves included, whatever its post. */
    private Optional<RevisionSnapshot> revision(RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        if (id <= 0) {
            return Optional.empty();
        }
        return revisionRows.findRevision(site.siteId(), RevisionId.of(id)).map(RevisionEntity::toSnapshot);
    }

    private static RevisionOrder order(String orderby) {
        return switch (orderby) {
            case "id" -> RevisionOrder.ID;
            case "include" -> RevisionOrder.INCLUDE;
            case "relevance" -> RevisionOrder.RELEVANCE;
            case "slug" -> RevisionOrder.SLUG;
            case "include_slugs" -> RevisionOrder.INCLUDE_SLUGS;
            case "title" -> RevisionOrder.TITLE;
            default -> RevisionOrder.DATE;
        };
    }

    // ---- arguments

    /**
     * The list's arguments in WordPress's order: {@code context}, {@code page}, {@code per_page} (1 to 100, no
     * default: the list is unpaged without it), {@code search}, {@code exclude}, {@code include}, {@code offset},
     * {@code order} and {@code orderby}.
     */
    private static List<Arg> listArgs(ObjectNode schema) {
        List<Arg> args = new ArrayList<>();
        for (Arg arg : Paging.collectionArgs(schema, false)) {
            if (arg.name().equals("per_page")) {
                ObjectNode perPage = arg.schema().deepCopy();
                perPage.remove("default");
                args.add(new Arg(
                        arg.name(), perPage, false, Optional.empty(), arg.phase(), arg.sanitize(), arg.validate()));
            } else {
                args.add(arg);
            }
        }
        args.add(idList("exclude", "Ensure result set excludes specific IDs."));
        args.add(idList("include", "Limit result set to specific IDs."));
        args.add(Paging.offsetArg());
        ObjectNode order = RestJson.NODES.objectNode();
        order.put("description", "Order sort attribute ascending or descending.");
        order.put("type", "string");
        order.put("default", "desc");
        order.putArray("enum").add("asc").add("desc");
        args.add(query("order", order));
        ObjectNode orderby = RestJson.NODES.objectNode();
        orderby.put("description", "Sort collection by object attribute.");
        orderby.put("type", "string");
        orderby.put("default", "date");
        ArrayNode orderings = orderby.putArray("enum");
        for (String ordering : List.of("date", "id", "include", "relevance", "slug", "include_slugs", "title")) {
            orderings.add(ordering);
        }
        args.add(query("orderby", orderby));
        return args;
    }

    /** A route-level integer such as {@code parent} or {@code id}, checked while the arguments are sanitized. */
    private static Arg integerArg(String name, String description) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", "integer");
        return new Arg(name, schema, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    private static Arg idList(String name, String description) {
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("description", description);
        list.put("type", "array");
        list.putObject("items").put("type", "integer");
        list.putArray("default");
        return query(name, list);
    }

    /** The delete endpoint's {@code force}: a boolean, default false, which WordPress requires to be true. */
    private static Arg forceArg() {
        ObjectNode force = RestJson.NODES.objectNode();
        force.put("type", "boolean");
        force.put("default", false);
        force.put("description", "Required to be true, as revisions do not support trashing.");
        return new Arg(
                "force",
                force,
                false,
                Optional.of(RestJson.NODES.booleanNode(false)),
                Arg.Phase.SANITIZE,
                Optional.empty());
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

    // ---- errors

    private static RestError invalidParent() {
        return RestError.of("rest_post_invalid_parent", "Invalid post parent ID.", 404);
    }

    private static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid revision ID.", 404);
    }

    private static RestError cannotRead(RestRequest request) {
        return RestError.of(
                "rest_cannot_read", "Sorry, you are not allowed to view revisions of this post.", request.authCode());
    }

    private static RestError cannotDeleteRevision(RestRequest request) {
        return RestError.of(
                "rest_cannot_delete", "Sorry, you are not allowed to delete this revision.", request.authCode());
    }
}
