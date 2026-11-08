package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.posts.runtime.PostsConfig;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * WordPress's content routes for every post type shown in REST but attachments and blocks (which have controllers
 * of their own), in registration order: {@code /<namespace>/<base>} (the collection) and
 * {@code /<namespace>/<base>/<id>} (the item), where the base is the type's REST base or its key. Posts are read
 * through the posts module for the request's site, which shows each caller only what it may read, so the totals count
 * readable posts.
 *
 * <p>An item is looked up on the request's site and must be of the route's type, else it does not exist. Its checks
 * run in WordPress's order: the edit context needs {@code edit_post}; a {@code password} in the query string that
 * differs from the stored one is refused, even on a post without password; then the post must be readable (its
 * status public, {@code read_post}, or an inherited status whose parent is readable and of a type shown in REST). The
 * edit context of a caller who may edit the post, or a matching {@code password} from any source, lifts the
 * protection of a post with a password. A viewable type's item carries a {@code Link} to its permalink in place of
 * the API root's. POST, PUT and PATCH on an item update it alike, with WordPress's update checks. DELETE moves the
 * post to the trash, or with {@code force} deletes it for good, for a caller with {@code delete_post}; both answers
 * are in the edit context. A type that keeps revisions gets WordPress's revisions routes right after its own, while
 * the revisions module is present.
 */
@ApplicationScoped
public class PostsController implements RestRouteProvider {

    /** The types whose routes other controllers serve. */
    private static final List<String> OWN_CONTROLLERS = List.of("attachment", "wp_block");

    private volatile Map<String, PostRoutes> served = Map.of();

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    SecurityIdentity identity;

    @Inject
    MetaFields meta;

    @Inject
    PostWriter writer;

    @Inject
    Posts posts;

    @Inject
    UserProfiles profiles;

    @Inject
    PostUpdateChecks updateChecks;

    @Inject
    PostCollection collection;

    @Inject
    PostFields fields;

    @Inject
    PostRepository rows;

    @Inject
    SiteContext site;

    @Inject
    ItemReads reads;

    @Inject
    RestUrls urls;

    @Inject
    PostsConfig postsConfig;

    /** The revisions routes; a bean only while the revisions module is present. */
    @Inject
    Instance<TypeRouteExtension> revisionRoutes;

    /** The autosaves routes; a bean only while the revisions module is present. */
    @Inject
    Instance<TypeRouteExtension.Autosaves> autosaveRoutes;

    @Override
    public int order() {
        return 50;
    }

    /**
     * The routes of every post type served here, by type key, in registration order, as of the last route table
     * build. {@link #register} is the only writer: it swaps in a new immutable map each time the route table is
     * built, so requests read a consistent snapshot. Controllers registered after this one (a higher
     * {@link #order()}) read the map of the same build.
     */
    Map<String, PostRoutes> routes() {
        return served;
    }

    @Override
    public void register(RouteRegistry routes) {
        List<String> allStatuses = new ArrayList<>();
        for (PostStatusDefinition status : statuses.all()) {
            allStatuses.add(status.name());
        }
        List<String> creatable = PostRoutes.creatable(statuses.all());
        Map<String, PostRoutes> built = new LinkedHashMap<>();
        for (PostTypeDefinition type : types.all()) {
            if (!type.showInRest() || OWN_CONTROLLERS.contains(type.key())) {
                continue;
            }
            built.put(type.key(), PostRoutes.of(type, taxonomies.all(), meta, creatable));
        }
        served = Collections.unmodifiableMap(built);
        for (PostRoutes post : built.values()) {
            PostTypeDefinition type = post.type();
            register(routes, post, allStatuses);
            // the revisions routes follow the type's own, for a type that keeps revisions, while the module is here
            if (revisionRoutes.isResolvable() && post.supports("revisions")) {
                revisionRoutes.get().register(routes, post);
            }
            // then the autosaves routes, for a type that is autosaved
            if (autosaveRoutes.isResolvable() && types.supports(type.key(), "autosave")) {
                autosaveRoutes.get().register(routes, post);
            }
        }
    }

    private void register(RouteRegistry routes, PostRoutes post, List<String> allStatuses) {
        routes.route(post.namespace(), "/" + post.base())
                .schema(() -> PostSchema.withLinks(post, urls))
                .endpoint(Endpoint.of("GET")
                        .args(PostSchema.collectionArgs(post, allStatuses, () -> identity, permissions))
                        .handler(request -> collection.list(post, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(PostSchema.writeArgs(post, Optional::empty))
                        .permission(request -> mayCreate(post, request))
                        .handler(request -> create(post, request))
                        .allowBatch(true)
                        .build())
                .register();
        routes.route(post.namespace(), "/" + post.base() + "/(?P<id>[\\d]+)")
                .arg(PostSchema.idArg())
                .schema(() -> PostSchema.withLinks(post, urls))
                .endpoint(Endpoint.of("GET")
                        .args(PostSchema.itemArgs(post))
                        .permission(request -> mayRead(post, request))
                        .handler(request -> item(post, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(PostSchema.writeArgs(post, () -> updateChecks.storedWhileChecking(post)))
                        .permission(request -> updateChecks.update(post, request))
                        .handler(request -> update(post, request))
                        .allowBatch(true)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(PostSchema.forceArg())
                        .permission(request -> mayDelete(post, request))
                        .handler(request -> delete(post, request))
                        .allowBatch(true)
                        .build())
                .register();
    }

    // ---- permissions

    /**
     * WordPress's create checks, in its order: no {@code id}; an author other than the caller needs the type's
     * {@code edit_others_posts}; a truthy {@code sticky}, for any type, needs its {@code edit_others_posts} and
     * {@code publish_posts}; the type's {@code create_posts}; then each named term that exists in its taxonomy must be
     * assignable.
     */
    private PermissionResult mayCreate(PostRoutes routes, RestRequest request) {
        if (request.param("id").filter(id -> !PhpValues.isEmpty(id)).isPresent()) {
            return PermissionResult.denied(RestError.of("rest_post_exists", "Cannot create existing post.", 400));
        }
        SecurityIdentity caller = request.identity();
        if (PostUpdateChecks.otherAuthor(request) && !permissions.canType(caller, "edit_others_posts", routes.key())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit_others",
                    "Sorry, you are not allowed to create posts as this user.",
                    request.authCode()));
        }
        // a type without the field leaves the value unsanitized: PHP's truthiness decides
        if (request.param("sticky").filter(sticky -> !PhpValues.isEmpty(sticky)).isPresent()
                && !(permissions.canType(caller, "edit_others_posts", routes.key())
                        && permissions.canType(caller, "publish_posts", routes.key()))) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_assign_sticky",
                    "Sorry, you are not allowed to make posts sticky.",
                    request.authCode()));
        }
        if (!permissions.canType(caller, "create_posts", routes.key())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_create",
                    "Sorry, you are not allowed to create posts as this user.",
                    request.authCode()));
        }
        if (!updateChecks.mayAssignTerms(routes, request)) {
            return PermissionResult.denied(PostUpdateChecks.cannotAssignTerm(request));
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayRead(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> found = existing(routes, request);
        if (found.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        PostSnapshot post = found.get();
        SecurityIdentity caller = request.identity();
        if (request.isEditContext() && !permissions.can(caller, "edit_post", post)) {
            return PermissionResult.denied(RestError.of(
                    "rest_forbidden_context", "Sorry, you are not allowed to edit this post.", request.authCode()));
        }
        Optional<String> given = queryPassword(request);
        if (given.isPresent() && !PostPasswords.matches(post, given.get())) {
            return PermissionResult.denied(
                    RestError.of("rest_post_incorrect_password", "Incorrect post password.", 403));
        }
        if (!permissions.restReadable(caller, post)) {
            return PermissionResult.forbidden(request);
        }
        return PermissionResult.allowed();
    }

    /** WordPress's delete check: the post of the routes' type must exist; the caller needs {@code delete_post}. */
    private PermissionResult mayDelete(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> found = existing(routes, request);
        if (found.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        if (!permissions.can(request.identity(), "delete_post", found.get())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_delete", "Sorry, you are not allowed to delete this post.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    // ---- handlers

    /**
     * Prepares the request (its errors come before anything is written), creates the post in one transaction and
     * answers 201 with the post in the edit context, as WordPress answers whatever context was asked for.
     */
    private RestResponse create(PostRoutes routes, RestRequest request) {
        PostRequest prepared = PostRequest.prepare(routes, request, Optional.empty(), services());
        PostSnapshot created = writer.create(routes, prepared, request);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        boolean lifted = permissions.can(request.identity(), "edit_post", created);
        return fields.prepare(routes, created, selection, "edit", request, lifted)
                .withStatus(201)
                .withHeader("Location", urls.route(routes.item(created.id())));
    }

    /**
     * Prepares the request against the stored post (its errors come before anything is written), updates the post in
     * one transaction and answers 200 with the post in the edit context, as WordPress answers whatever context was
     * asked for. The request's later reads of the post (its {@code Allow}) see the post as updated.
     */
    private RestResponse update(PostRoutes routes, RestRequest request) {
        PostSnapshot stored = existing(routes, request).orElseThrow(() -> new RestException(invalidId()));
        PostRequest prepared = PostRequest.prepare(routes, request, Optional.of(stored), services());
        PostSnapshot updated = writer.update(routes, stored, prepared, request);
        reads.remember(routes, updated);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        boolean lifted = permissions.can(request.identity(), "edit_post", updated);
        return fields.prepare(routes, updated, selection, "edit", request, lifted);
    }

    /**
     * With {@code force}, renders the post in the edit context, deletes it for good and answers
     * {@code {"deleted": true, "previous": <that rendering without links>}}; the request's later reads (its
     * {@code Allow}) no longer find it. Without, the trash must be on, kept a positive number of days (else 501), and
     * the post not in it already (else 410); the post is trashed and answered in the edit context, with its links.
     * WordPress answers whatever context was asked for in the edit context.
     */
    private RestResponse delete(PostRoutes routes, RestRequest request) {
        PostSnapshot stored = existing(routes, request).orElseThrow(() -> new RestException(invalidId()));
        boolean force = request.param("force").map(PhpValues::toBoolean).orElse(false);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        if (force) {
            boolean lifted = permissions.can(request.identity(), "edit_post", stored);
            JsonNode previous = fields.prepare(routes, stored, selection, "edit", request, lifted)
                    .body();
            if (posts.delete(stored.id(), true).isEmpty()) {
                throw new RestException(cannotDelete());
            }
            reads.forget(routes, stored.id());
            ObjectNode body = RestJson.NODES.objectNode();
            body.set("deleted", BooleanNode.TRUE);
            body.set("previous", previous);
            return RestResponse.ok(body);
        }
        if (postsConfig.emptyTrashDays() <= 0) {
            throw new RestException(RestError.of(
                    "rest_trash_not_supported",
                    "The post does not support trashing. Set 'force=true' to delete.",
                    501));
        }
        if (stored.status().equals("trash")) {
            throw new RestException(RestError.of("rest_already_trashed", "The post has already been deleted.", 410));
        }
        // the trash and the read of the trashed post share one transaction, so the read sees the write
        PostSnapshot trashed = QuarkusTransaction.joiningExisting().call(() -> {
            if (posts.trash(stored.id()).isEmpty()) {
                throw new RestException(cannotDelete());
            }
            return rows.findPost(site.siteId(), stored.id())
                    .map(PostEntity::toSnapshot)
                    .orElseThrow(() -> new RestException(cannotDelete()));
        });
        reads.remember(routes, trashed);
        boolean lifted = permissions.can(request.identity(), "edit_post", trashed);
        return fields.prepare(routes, trashed, selection, "edit", request, lifted);
    }

    private PostRequest.Services services() {
        return new PostRequest.Services(permissions, statuses, posts, rows, profiles, site);
    }

    private RestResponse item(PostRoutes routes, RestRequest request) {
        PostSnapshot post = existing(routes, request).orElseThrow(() -> new RestException(invalidId()));
        boolean lifted = (request.isEditContext() && permissions.can(request.identity(), "edit_post", post))
                || PostFields.givenPassword(request)
                        .map(given -> PostPasswords.matches(post, given))
                        .orElse(false);
        Fields.Selection selection = Fields.forResponse(request, routes.schema());
        RestResponse response = fields.prepare(routes, post, selection, request.context(), request, lifted);
        if (PostPermissions.typeViewable(routes.type())) {
            response = response.withHeader(
                    "Link", "<" + fields.link(routes.type(), post) + ">; rel=\"alternate\"; type=text/html");
        }
        return response;
    }

    /**
     * The post of this site with the route's id and type, read without any capability check, once per request: the
     * permission callback, the {@code Allow} computation and the handler share the read.
     */
    private Optional<PostSnapshot> existing(PostRoutes routes, RestRequest request) {
        return reads.post(routes, request, "id");
    }

    /** The item reads of one request, by type and id: a GET reads nothing twice. */
    @RequestScoped
    public static class ItemReads {

        final Map<String, Optional<PostSnapshot>> found = new HashMap<>();

        @Inject
        PostRepository rows;

        @Inject
        SiteContext site;

        /**
         * The post of this site whose id is the request's {@code param} (a route parameter), of the routes' type, read
         * without any capability check and at most once per request; none for an id that is not positive.
         */
        Optional<PostSnapshot> post(PostRoutes routes, RestRequest request, String param) {
            long id = request.param(param).map(PhpValues::toLong).orElse(0L);
            if (id <= 0) {
                return Optional.empty();
            }
            return found.computeIfAbsent(
                    routes.key() + "/" + id,
                    key -> rows.findPost(site.siteId(), PostId.of(id))
                            .map(PostEntity::toSnapshot)
                            .filter(post -> post.type().equals(routes.key())));
        }

        /** Replaces the read of a post this request wrote, so that its later reads see the write. */
        void remember(PostRoutes routes, PostSnapshot post) {
            found.put(routes.key() + "/" + post.id().value(), Optional.of(post));
        }

        /** Replaces the read of a post this request deleted: its later reads find none. */
        void forget(PostRoutes routes, PostId post) {
            found.put(routes.key() + "/" + post.value(), Optional.empty());
        }
    }

    /** The {@code password} of the query string alone, when it is a text that is not empty in PHP's sense. */
    private static Optional<String> queryPassword(RestRequest request) {
        JsonNode value = request.queryParams().get("password");
        return value != null && value.isTextual() && !PhpValues.isEmpty(value)
                ? Optional.of(value.textValue())
                : Optional.empty();
    }

    private static RestError cannotDelete() {
        return RestError.of("rest_cannot_delete", "The post cannot be deleted.", 500);
    }

    private static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid post ID.", 404);
    }
}
