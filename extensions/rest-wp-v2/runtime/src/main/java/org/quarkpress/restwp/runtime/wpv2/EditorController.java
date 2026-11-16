package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * What the block editor gets from WordPress's edit screen before its first request, over REST:
 * {@code GET /quarkpress/v1/editor/<type>/<id>} answers {@code {settings, preload, blockDefinitions, dateSettings}}
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

    private static final String NAMESPACE = "quarkpress/v1";

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

    /** After the other {@code quarkpress/v1} routes. */
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
