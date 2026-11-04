package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.PreviewTokens;
import org.quarkpress.posts.RegisteredMeta;
import org.quarkpress.posts.runtime.PostRepository;
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
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.security.UserProfiles;

/**
 * WordPress's autosaves routes of a post type: {@code /<namespace>/<base>/<id>/autosaves} (the list of every user's
 * autosaves of the post, newest first and unpaged, and the autosave itself) and
 * {@code /<namespace>/<base>/<parent>/autosaves/<id>} (the latest autosave of the post, of any user, whatever the id).
 * Reading needs {@code edit_post} on the post. An autosave is checked as an update of the post and its request
 * prepared as one (a preparation error is the answer); then a draft of the caller's own that no other user holds a
 * lock on is updated in place with every prepared field but its meta, terms, featured image, stickiness, format and
 * template, and answered in the revision shape; any other post gets the caller's autosave, with the request's
 * revisioned meta, created, rewritten or kept as it is when nothing differs from the post. The autosave answers 200
 * in the edit context. In the edit context every answer carries its {@code preview_link}: the post's own for a draft
 * updated in place, else the post's with the caller's preview token. A bean only while the revisions module is
 * present: the build removes it otherwise, and the posts controller reaches it as a
 * {@link TypeRouteExtension.Autosaves}.
 */
@ApplicationScoped
class AutosaveRoutes implements TypeRouteExtension.Autosaves {

    private static final String AUTOSAVES = "/autosaves";

    private static final String PARENT = "The ID for the parent of the autosave.";

    private static final String ID = "The ID for the autosave.";

    @Inject
    Revisions revisions;

    @Inject
    PostPermissions permissions;

    @Inject
    PostStatuses statuses;

    @Inject
    Posts posts;

    @Inject
    PostRepository rows;

    @Inject
    UserProfiles profiles;

    @Inject
    SiteContext site;

    @Inject
    PreviewTokens previewTokens;

    @Inject
    RegisteredMeta registeredMeta;

    @Inject
    PostUpdateChecks updateChecks;

    @Inject
    PostsController.ItemReads reads;

    @Inject
    RevisionFields fields;

    @Inject
    PostFields postFields;

    /** Registers the two routes of {@code post}'s type, after the type's revisions routes. */
    @Override
    public void register(RouteRegistry routes, PostRoutes post) {
        ObjectNode schema = schema(post);
        // the route names its argument parent, while the path calls the post id
        routes.route(post.namespace(), "/" + post.base() + "/(?P<id>[\\d]+)" + AUTOSAVES)
                .arg(integerArg("parent", PARENT))
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .permission(request -> mayList(post, request))
                        .handler(request -> list(post, schema, request))
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(PostSchema.writeArgs(post, () -> updateChecks.storedWhileChecking(post)))
                        .permission(request -> mayCreate(post, request))
                        .handler(request -> create(post, schema, request))
                        .build())
                .register();
        routes.route(post.namespace(), "/" + post.base() + "/(?P<parent>[\\d]+)" + AUTOSAVES + "/(?P<id>[\\d]+)")
                .arg(integerArg("parent", PARENT))
                .arg(integerArg("id", ID))
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(schema))
                        .permission(request -> mayRead(post, request))
                        .handler(request -> item(post, schema, request))
                        .build())
                .register();
    }

    /** The type's revision schema with the autosave's {@code preview_link}, an edit-only, read-only URI. */
    private ObjectNode schema(PostRoutes routes) {
        ObjectNode schema = fields.schema(routes).deepCopy();
        ObjectNode link = ((ObjectNode) schema.get("properties")).putObject("preview_link");
        link.put("description", "Preview link for the post.");
        link.put("type", "string");
        link.put("format", "uri");
        link.putArray("context").add("edit");
        link.put("readonly", true);
        return schema;
    }

    // ---- permissions

    /** The post of the path's {@code id} exists under the route's type and the caller may edit it. */
    private PermissionResult mayList(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> parent = reads.post(routes, request, "id");
        if (parent.isEmpty()) {
            return PermissionResult.denied(invalidParent());
        }
        if (!permissions.can(request.identity(), "edit_post", parent.get())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_read",
                    "Sorry, you are not allowed to view autosaves of this post.",
                    request.authCode()));
        }
        return PermissionResult.allowed();
    }

    /** The revisions routes' read check, against the path's {@code parent}. */
    private PermissionResult mayRead(PostRoutes routes, RestRequest request) {
        Optional<PostSnapshot> parent = reads.post(routes, request, "parent");
        if (parent.isEmpty()) {
            return PermissionResult.denied(invalidParent());
        }
        if (!permissions.can(request.identity(), "edit_post", parent.get())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_read",
                    "Sorry, you are not allowed to view revisions of this post.",
                    request.authCode()));
        }
        return PermissionResult.allowed();
    }

    /**
     * An id empty in PHP's sense names no item; then WordPress's update checks of the post (the posts route's own).
     */
    private PermissionResult mayCreate(PostRoutes routes, RestRequest request) {
        if (request.param("id").map(PhpValues::isEmpty).orElse(true)) {
            return PermissionResult.denied(RestError.of("rest_post_invalid_id", "Invalid item ID.", 404));
        }
        return updateChecks.update(routes, request);
    }

    // ---- handlers

    /** Every user's autosaves of the post, newest first; no paging and no totals. */
    private RestResponse list(PostRoutes routes, ObjectNode schema, RestRequest request) {
        PostSnapshot parent = reads.post(routes, request, "id").orElseThrow(() -> new RestException(invalidParent()));
        ArrayNode items = RestJson.NODES.arrayNode();
        if (!request.method().equals("HEAD")) {
            Fields.Selection selection = Fields.forResponse(request, schema);
            Supplier<String> link = previewLink(routes, parent, request);
            for (RevisionSnapshot autosave : revisions.autosavesOf(parent.id())) {
                items.add(CollectionItems.inline(
                        withPreviewLink(
                                fields.prepare(routes, autosave, selection, request.context()), selection, link),
                        request));
            }
        }
        return RestResponse.ok(items);
    }

    /** The post's latest autosave of any user, whatever the path's id. */
    private RestResponse item(PostRoutes routes, ObjectNode schema, RestRequest request) {
        PostSnapshot parent =
                reads.post(routes, request, "parent").orElseThrow(() -> new RestException(invalidParent()));
        RevisionSnapshot autosave = revisions
                .autosaveOf(parent.id(), Optional.empty())
                .orElseThrow(() -> new RestException(
                        RestError.of("rest_post_no_autosave", "There is no autosave revision for this post.", 404)));
        Fields.Selection selection = Fields.forResponse(request, schema);
        return withPreviewLink(
                fields.prepare(routes, autosave, selection, request.context()),
                selection,
                previewLink(routes, parent, request));
    }

    /**
     * Prepares the request as an update of the post (its first error is the answer, before anything is written),
     * then autosaves: the post itself when it is a draft of the caller's own that nobody else holds, else the
     * caller's autosave. Answers 200 in the edit context, whatever context was asked for.
     */
    private RestResponse create(PostRoutes routes, ObjectNode schema, RestRequest request) {
        PostSnapshot stored = reads.post(routes, request, "id")
                .orElseThrow(() -> new RestException(RestError.of("rest_post_invalid_id", "Invalid post ID.", 404)));
        PostRequest prepared = PostRequest.prepare(
                routes,
                request,
                Optional.of(stored),
                new PostRequest.Services(permissions, statuses, posts, rows, profiles, site));
        AutosaveChanges.Builder changes = AutosaveChanges.builder().post(prepared.changes());
        prepared.changes().title().ifPresent(changes::title);
        prepared.changes().content().ifPresent(changes::content);
        prepared.changes().excerpt().ifPresent(changes::excerpt);
        prepared.meta().filter(JsonNode::isObject).ifPresent(meta -> revisionedMeta(routes, stored, meta, changes));
        AutosaveResult result;
        try {
            result = revisions.autosave(stored.id(), changes.build());
        } catch (PostException refused) {
            throw new RestException(PostWriter.insertError(refused));
        }
        request.useContext("edit");
        Fields.Selection selection = Fields.forResponse(Fields.of(request), schema, "edit", request.hasParam("_embed"));
        return switch (result) {
            case AutosaveResult.Draft draft -> {
                // the request's later reads of the post (its Allow) see the post as updated
                reads.remember(routes, draft.post());
                yield withPreviewLink(
                        fields.prepareOwnDraft(routes, draft.post(), selection, "edit"),
                        selection,
                        () -> PreviewLinks.of(
                                routes, draft.post(), postFields.link(routes.type(), draft.post()), Optional.empty()));
            }
            case AutosaveResult.Stored autosave ->
                withPreviewLink(
                        fields.prepare(routes, autosave.autosave(), selection, "edit"),
                        selection,
                        previewLink(routes, stored, request));
        };
    }

    /**
     * The request's values of the type's revisioned meta keys shown in REST, each sanitized by the posts module as a
     * write of the post's meta sanitizes it (the key's sanitizer for the caller, the value checked as storable); a
     * key given null is left out, and the other keys are ignored.
     */
    private void revisionedMeta(PostRoutes routes, PostSnapshot post, JsonNode meta, AutosaveChanges.Builder changes) {
        Set<String> revisioned = registeredMeta.revisionedKeys(routes.key());
        for (MetaKeyDefinition key : registeredMeta.restKeys(routes.key())) {
            JsonNode value = meta.get(key.restKey());
            if (value == null || value.isNull() || !revisioned.contains(key.key())) {
                continue;
            }
            changes.meta(key.key(), registeredMeta.sanitize(post, key, value));
        }
    }

    // ---- preview links

    /**
     * The post's preview link with the caller's preview token, computed once and only when an answer asks for it:
     * the token is the same for a whole tick, so the answers of one request, and of the editor's repeated requests,
     * agree.
     */
    private Supplier<String> previewLink(PostRoutes routes, PostSnapshot post, RestRequest request) {
        String[] computed = new String[1];
        return () -> {
            if (computed[0] == null) {
                // a type that is not viewable has no preview link, so no token is issued for it
                Optional<String> token = PostPermissions.typeViewable(routes.type())
                        ? request.user().map(user -> previewTokens.issue(post.id(), user))
                        : Optional.empty();
                computed[0] = PreviewLinks.of(routes, post, postFields.link(routes.type(), post), token);
            }
            return computed[0];
        };
    }

    /** The answer with its {@code preview_link} after the revision's fields, when the selection keeps it. */
    private static RestResponse withPreviewLink(
            RestResponse answer, Fields.Selection selection, Supplier<String> link) {
        if (selection.includes("preview_link") && answer.body() instanceof ObjectNode body) {
            body.put("preview_link", link.get());
        }
        return answer;
    }

    // ---- arguments and errors

    /** A route-level integer such as {@code parent} or {@code id}, checked while the arguments are sanitized. */
    private static Arg integerArg(String name, String description) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", "integer");
        return new Arg(name, schema, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    private static RestError invalidParent() {
        return RestError.of("rest_post_invalid_parent", "Invalid post parent ID.", 404);
    }
}
