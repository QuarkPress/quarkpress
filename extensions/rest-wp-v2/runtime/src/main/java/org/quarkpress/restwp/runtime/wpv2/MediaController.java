package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.media.ImageSizes;
import org.quarkpress.media.MediaFeature;
import org.quarkpress.media.MimeTypes;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * WordPress's media routes, registered while the attachment type is shown in REST, after the other post types'
 * routes, in WordPress's order: {@code /wp/v2/media} (the collection and the upload), {@code /wp/v2/media/<id>} (the
 * item, its update and its delete) and {@code /wp/v2/media/<id>/post-process} (making a stored image's missing
 * sizes), with their arguments and the attachment schema. No media route can be batched. The image editing route
 * ({@code /edit}) and the client-side upload routes are not served, nor are revisions or autosaves of attachments.
 *
 * <p>An endpoint not served yet answers 404 {@code rest_no_route}, as a request no route matches, so that its
 * method is left out of {@code Allow}.
 */
@ApplicationScoped
public class MediaController implements RestRouteProvider, MediaRoutesPresent {

    private static final String TYPE = "attachment";

    private volatile PostRoutes built;

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
    RestUrls urls;

    @Inject
    ImageSizes sizes;

    @Inject
    MimeTypes mimeTypes;

    @Inject
    SiteContext site;

    @Override
    public int order() {
        return 60;
    }

    /**
     * The attachment's routes as of the last route table build; null while attachments are not shown in REST.
     * {@link #register} is the only writer.
     */
    PostRoutes routes() {
        return built;
    }

    @Override
    public boolean served() {
        return built != null && site.features().isEnabled(MediaFeature.KEY);
    }

    @Override
    public void register(RouteRegistry routes) {
        Optional<PostTypeDefinition> attachment = types.find(TYPE).filter(PostTypeDefinition::showInRest);
        if (attachment.isEmpty()) {
            built = null;
            return;
        }
        PostRoutes posts =
                PostRoutes.of(attachment.get(), taxonomies.all(), meta, PostRoutes.creatable(statuses.all()));
        PostRoutes media = new PostRoutes(
                posts.type(), posts.namespace(), posts.base(), MediaSchema.of(posts), posts.taxonomies());
        built = media;
        routes.route(media.namespace(), "/" + media.base())
                .schema(() -> schema(media))
                .endpoint(Endpoint.of("GET")
                        .args(MediaSchema.collectionArgs(media, () -> identity, permissions, mimeTypes.patterns()))
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(MediaSchema.createArgs(media))
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .register();
        routes.route(media.namespace(), "/" + media.base() + "/(?P<id>[\\d]+)")
                .arg(PostSchema.idArg())
                .schema(() -> schema(media))
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(media.schema()))
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(MediaSchema.updateArgs(media, Optional::empty))
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(PostSchema.forceArg())
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .register();
        routes.route(media.namespace(), "/" + media.base() + "/(?P<id>[\\d]+)/post-process")
                .endpoint(Endpoint.of("POST")
                        .arg(MediaSchema.postProcessIdArg())
                        .arg(MediaSchema.actionArg())
                        .permission(MediaController::notServedYet)
                        .handler(MediaController::notServed)
                        .allowBatch(false)
                        .build())
                .register();
    }

    /**
     * The item schema with its links and the current site's image sizes; without a site (the check of the schema
     * while the routes are registered) it lists no size.
     */
    private ObjectNode schema(PostRoutes media) {
        ObjectNode schema = MediaSchema.withLinks(media, urls);
        return site.isResolved()
                ? MediaSchema.withSizes(schema, sizes.registered().keySet())
                : schema;
    }

    /** The answer of an endpoint not served yet: the one a request no route matches. */
    private static PermissionResult notServedYet(RestRequest request) {
        return PermissionResult.denied(noRoute());
    }

    private static RestResponse notServed(RestRequest request) {
        throw new RestException(noRoute());
    }

    private static RestError noRoute() {
        return RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404);
    }
}
