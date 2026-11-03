package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
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
 * status public, {@code read_post}, or an inherited status whose parent is readable). The edit context of a caller
 * who may edit the post, or a matching {@code password} from any source, lifts the protection of a post with a
 * password. A viewable type's item carries a {@code Link} to its permalink in place of the API root's. A type that
 * keeps revisions gets WordPress's revisions routes right after its own, while the revisions module is present.
 */
@ApplicationScoped
public class PostsController implements RestRouteProvider {

    /** The types whose routes other controllers serve. */
    private static final List<String> OWN_CONTROLLERS = List.of("attachment", "wp_block");

    /** The deepest chain of inherited statuses followed when deciding whether a post is readable. */
    private static final int MAX_INHERITANCE = 100;

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

    /** The revisions routes; a bean only while the revisions module is present. */
    @Inject
    Instance<TypeRouteExtension> revisionRoutes;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void register(RouteRegistry routes) {
        List<String> allStatuses = new ArrayList<>();
        for (PostStatusDefinition status : statuses.all()) {
            allStatuses.add(status.name());
        }
        List<String> creatable = PostRoutes.creatable(statuses.all());
        for (PostTypeDefinition type : types.all()) {
            if (!type.showInRest() || OWN_CONTROLLERS.contains(type.key())) {
                continue;
            }
            PostRoutes post = PostRoutes.of(type, taxonomies.all(), meta, creatable);
            register(routes, post, allStatuses);
            // the revisions routes follow the type's own, for a type that keeps revisions, while the module is here
            if (revisionRoutes.isResolvable() && post.supports("revisions")) {
                revisionRoutes.get().register(routes, post);
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
                .register();
    }

    // ---- permissions

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
        if (!readable(caller, post)) {
            return PermissionResult.forbidden(request);
        }
        return PermissionResult.allowed();
    }

    /**
     * WordPress's read decision: a published post, {@code read_post}, a public status, or an inherited status whose
     * parent is readable (a missing parent reads); the chain of parents is followed until a post repeats, and a chain
     * deeper than {@value #MAX_INHERITANCE} is not readable.
     */
    private boolean readable(SecurityIdentity caller, PostSnapshot post) {
        Set<Long> seen = new HashSet<>();
        PostSnapshot current = post;
        while (true) {
            if (current.status().equals("publish")
                    || permissions.can(caller, "read_post", current)
                    || statuses.find(current.status())
                            .map(PostStatusDefinition::publicly)
                            .orElse(false)) {
                return true;
            }
            if (!current.status().equals("inherit") || !seen.add(current.id().value())) {
                return false;
            }
            if (seen.size() > MAX_INHERITANCE) {
                // a chain too deep to decide is not granted
                return false;
            }
            if (current.parent().isEmpty()) {
                return true;
            }
            Optional<PostSnapshot> parent =
                    rows.findPost(site.siteId(), current.parent().get()).map(PostEntity::toSnapshot);
            if (parent.isEmpty()) {
                return true;
            }
            current = parent.get();
        }
    }

    // ---- handlers

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
    }

    /** The {@code password} of the query string alone, when it is a text that is not empty in PHP's sense. */
    private static Optional<String> queryPassword(RestRequest request) {
        JsonNode value = request.queryParams().get("password");
        return value != null && value.isTextual() && !PhpValues.isEmpty(value)
                ? Optional.of(value.textValue())
                : Optional.empty();
    }

    private static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid post ID.", 404);
    }
}
