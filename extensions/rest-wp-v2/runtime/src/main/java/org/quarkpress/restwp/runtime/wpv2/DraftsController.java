package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.restwp.Arg;
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
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * {@code POST /quarkpress/v1/drafts}: the auto-draft a new post starts from, as WordPress's new-post screen creates
 * it before the editor opens. The body names the {@code type} (a post type shown in REST, attachments and blocks
 * excepted); the caller needs the type's {@code edit_posts} and {@code create_posts}. The row is WordPress's: status
 * {@code auto-draft}, the title "Auto Draft" when the type has a title (else empty), the caller as author, a
 * floating date, no slug, the type's comment and ping defaults and no term; when the site's
 * {@code default_post_format} names a format and the type supports post formats, the post gets that format. The
 * answer is 201 with the post in the edit context and a {@code Location} header naming it under its type's routes.
 * No lock is taken here: the shell takes one through the lock routes right after, so the state matches WordPress's
 * after its new-post screen.
 */
@ApplicationScoped
public class DraftsController implements RestRouteProvider {

    private static final String NAMESPACE = "quarkpress/v1";

    private static final String DRAFTS = "/drafts";

    private static final String AUTO_DRAFT = "auto-draft";

    private static final String AUTO_DRAFT_TITLE = "Auto Draft";

    /** WordPress's default of {@code default_post_format}: no format. */
    private static final String NO_FORMAT = "0";

    /** The types whose routes other controllers serve: no auto-draft starts there. */
    private static final List<String> OWN_CONTROLLERS = List.of("attachment", "wp_block");

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    Posts posts;

    @Inject
    PostFormats formats;

    @Inject
    PostFields fields;

    @Inject
    MetaFields meta;

    @Inject
    RestUrls urls;

    @Inject
    SiteContext site;

    /**
     * The routes of every type an auto-draft may start in, by type key. {@link #register} is the only writer: it
     * swaps in a new immutable map each time the route table is built, so requests read a consistent snapshot.
     */
    private volatile Map<String, PostRoutes> routes = Map.of();

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public void register(RouteRegistry registry) {
        Map<String, PostRoutes> served = new LinkedHashMap<>();
        List<String> creatable = PostRoutes.creatable(statuses.all());
        for (PostTypeDefinition type : types.all()) {
            if (!type.showInRest() || OWN_CONTROLLERS.contains(type.key())) {
                continue;
            }
            served.put(type.key(), PostRoutes.of(type, taxonomies.all(), meta, creatable));
        }
        routes = Collections.unmodifiableMap(served);
        registry.route(NAMESPACE, DRAFTS)
                .endpoint(Endpoint.of("POST")
                        .arg(Arg.of("type", "string")
                                .description("The post type the new post starts in.")
                                .required()
                                .build())
                        .permission(this::mayCreate)
                        .handler(this::create)
                        .build())
                .register();
    }

    // ---- permission

    /** The type exists and is served here, and the caller has its {@code edit_posts} and {@code create_posts}. */
    private PermissionResult mayCreate(RestRequest request) {
        Optional<PostRoutes> type = requested(request);
        if (type.isEmpty()) {
            return PermissionResult.denied(invalidType());
        }
        SecurityIdentity identity = request.identity();
        String key = type.get().key();
        if (!permissions.canType(identity, "edit_posts", key) || !permissions.canType(identity, "create_posts", key)) {
            return PermissionResult.denied(cannotCreate(request));
        }
        return PermissionResult.allowed();
    }

    // ---- handler

    private RestResponse create(RestRequest request) {
        PostRoutes type = requested(request).orElseThrow(() -> new RestException(invalidType()));
        String format = site.options().string("default_post_format", NO_FORMAT);
        boolean withFormat = type.supports("post-formats")
                && !format.isEmpty()
                && !format.equals(NO_FORMAT)
                && PostFormats.SLUGS.contains(format);
        // the row and its format are one write
        PostSnapshot created = QuarkusTransaction.joiningExisting().call(() -> {
            PostSnapshot post = posts.create(changes(type, request));
            if (withFormat) {
                formats.set(post.id(), format);
            }
            return post;
        });
        // as WordPress's new-post screen hands the editor the post in the edit context
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), type.schema(), "edit", request.hasParam("_embed"));
        return fields.prepare(type, created, selection, "edit", request)
                .withStatus(201)
                .withHeader("Location", urls.route(type.item(created.id())));
    }

    /** WordPress's auto-draft insert: only the type, the status and the title; everything else is the default. */
    private static PostChanges changes(PostRoutes type, RestRequest request) {
        PostChanges.Builder changes = PostChanges.builder()
                .type(type.key())
                .status(AUTO_DRAFT)
                .title(type.supports("title") ? AUTO_DRAFT_TITLE : "");
        request.user().ifPresent(changes::author);
        return changes.build();
    }

    // ---- lookups

    /** The routes of the requested type, when it is a text naming a type served here. */
    private Optional<PostRoutes> requested(RestRequest request) {
        return request.param("type")
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .map(routes::get);
    }

    private static RestError invalidType() {
        return RestError.of("rest_type_invalid", "Invalid post type.", 404);
    }

    private static RestError cannotCreate(RestRequest request) {
        return RestError.of(
                "rest_cannot_create", "Sorry, you are not allowed to create posts as this user.", request.authCode());
    }
}
