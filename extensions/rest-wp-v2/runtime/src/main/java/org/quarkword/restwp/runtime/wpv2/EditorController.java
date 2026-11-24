package org.quarkword.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteContext;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;
import org.quarkword.restwp.Arg;
import org.quarkword.restwp.Endpoint;
import org.quarkword.restwp.PermissionResult;
import org.quarkword.restwp.RestError;
import org.quarkword.restwp.RestException;
import org.quarkword.restwp.RestRequest;
import org.quarkword.restwp.RestResponse;
import org.quarkword.restwp.RestRouteProvider;
import org.quarkword.restwp.RouteRegistry;
import org.quarkword.restwp.runtime.PhpValues;
import org.quarkword.restwp.runtime.RestJson;
import org.quarkword.taxonomy.Taxonomies;

/**
 * What the block editor gets from WordPress's edit screen before its first request, over REST:
 * {@code GET /quarkword/v1/editor/<type>/<id>} answers {@code {settings, preload, blockDefinitions, dateSettings}}
 * for a post of that type on this site: the settings object, the apiFetch preload map ({@link EditorPreload}, each
 * entry the caller's own answer of the routes of this server), the server block definitions and the site's date
 * settings the screen prints as {@code wp.date} settings ({@link EditorDateSettings}). The route reads and writes
 * nothing else: no lock is taken (the admin shell takes it through the locks route when it opens the post), and an
 * older autosave is left as it is, where WordPress's screen takes the lock and deletes the autosave.
 *
 * <p>An id that is not a post of {@code type} on this site, or a type not shown in REST, is {@code rest_post_invalid_id};
 * the caller needs {@code edit_post} on the post, else {@code rest_cannot_edit}.
 */
@ApplicationScoped
public class EditorController implements RestRouteProvider {

    private static final String NAMESPACE = "quarkword/v1";

    private static final String EDITOR = "/editor/(?P<type>[\\w-]+)/(?P<id>[\\d]+)";

    @Inject
    EditorSettings settings;

    @Inject
    EditorPreload preload;

    @Inject
    EditorDateSettings dateSettings;

    @Inject
    PostsController postsController;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostStatuses statuses;

    @Inject
    MetaFields meta;

    @Inject
    PostPermissions permissions;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    SiteContext site;

    /** After the other {@code quarkword/v1} routes. */
    @Override
    public int order() {
        return 1010;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route(NAMESPACE, EDITOR)
                .arg(Arg.of("type", "string")
                        .description("An alphanumeric identifier for the post type.")
                        .phase(Arg.Phase.SANITIZE)
                        .build())
                .arg(Arg.of("id", "integer")
                        .description("Unique identifier for the post.")
                        .phase(Arg.Phase.SANITIZE)
                        .build())
                .endpoint(Endpoint.of("GET")
                        .permission(this::mayEdit)
                        .handler(this::answer)
                        .build())
                .register();
    }

    /** The post must exist as the route names it, and the caller must hold {@code edit_post} on it. */
    private PermissionResult mayEdit(RestRequest request) {
        Optional<Edited> edited = requested(request);
        if (edited.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        if (!permissions.can(request.identity(), "edit_post", edited.get().post())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit", "Sorry, you are not allowed to edit this post.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private RestResponse answer(RestRequest request) {
        Edited edited = requested(request).orElseThrow(() -> new RestException(invalidId()));
        ObjectNode body = RestJson.NODES.objectNode();
        body.set("settings", settings.of(request, edited.post(), edited.type()));
        body.set("preload", preload.of(routesOf(edited.type()), edited.post(), request));
        body.set("blockDefinitions", settings.blockDefinitions());
        body.set("dateSettings", dateSettings.of());
        return RestResponse.ok(body);
    }

    /** The post the route names and its type. */
    private record Edited(PostSnapshot post, PostTypeDefinition type) {}

    /**
     * The REST routes of the type: the posts controller's for the types it serves, else (attachments, served by the
     * media controller) derived the same way, for the type's base and features.
     */
    private PostRoutes routesOf(PostTypeDefinition type) {
        PostRoutes routes = postsController.routes().get(type.key());
        if (routes != null) {
            return routes;
        }
        return PostRoutes.of(type, taxonomies.all(), meta, PostRoutes.creatable(statuses.all()));
    }

    /**
     * The post of this site with the route's id whose type is the route's type and is shown in REST, read without any
     * capability check; empty for {@code 0}, which the digits-only group admits and no post has.
     */
    private Optional<Edited> requested(RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        String key = request.param("type")
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .orElse("");
        if (id <= 0) {
            return Optional.empty();
        }
        Optional<PostTypeDefinition> type = types.find(key).filter(PostTypeDefinition::showInRest);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        return posts.findPost(site.siteId(), PostId.of(id))
                .map(PostEntity::toSnapshot)
                .filter(post -> post.type().equals(key))
                .map(post -> new Edited(post, type.get()));
    }

    private static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid post ID.", 404);
    }
}
