package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.media.ImageSizes;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaFeature;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MimeTypes;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.UploadedFile;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.Capabilities;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * WordPress's media routes, registered while the attachment type is shown in REST, after the other post types'
 * routes, in WordPress's order: {@code /wp/v2/media} (the collection and the upload), {@code /wp/v2/media/<id>} (the
 * item, its update and its delete) and {@code /wp/v2/media/<id>/post-process} (making a stored image's missing
 * sizes), with their arguments and the attachment schema. No media route can be batched. The image editing route
 * ({@code /edit}) and the client-side upload routes are not served, nor are revisions or autosaves of attachments.
 * On a site whose media feature is off, every media request answers as no route would.
 *
 * <p>An item is an attachment of the request's site, any other id does not exist. Its checks run in WordPress's
 * order: the edit context needs {@code edit_post}; any {@code password} in the query string is refused (an
 * attachment never has one); then the attachment must be readable (an inherited status reads through its parent).
 * The item carries a {@code Link} to its page in place of the API root's. The collection lists the site's library
 * ({@link MediaCollection}); its edit context needs the attachment type's {@code edit_posts}.
 *
 * <p>An upload is checked in WordPress's order: the create permission (which the collection's {@code Allow} follows),
 * the parent's type, then the body ({@link MediaUploadRequest}); it is then stored and answered by {@link MediaWriter}.
 * An update is checked as the posts routes check one (no upload capability is needed), then its parent's type; it is
 * written and answered by {@link MediaWriter} too, and never reads a file.
 *
 * <p>A delete is checked as the posts routes check one; there is no media trash, so only a forced delete is served,
 * which removes the attachment and its files. A post-process is checked as an update; it makes the attachment's missing
 * sizes and answers the attachment, whatever its state, as the editor's recovery of an upload that did not finish.
 */
@ApplicationScoped
public class MediaController implements RestRouteProvider, MediaRoutesPresent {

    private static final Logger LOG = Logger.getLogger(MediaController.class);

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

    @Inject
    MediaReads reads;

    @Inject
    MediaFields fields;

    @Inject
    PostFields postFields;

    @Inject
    MediaCollection collection;

    @Inject
    Media library;

    @Inject
    MediaWriter writer;

    @Inject
    PostUpdateChecks updateChecks;

    @Inject
    Capabilities capabilities;

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
                        .args(MediaSchema.collectionArgs(
                                media, () -> identity, permissions, mimeTypes.patterns(), this::allowedTypes))
                        .permission(this::mayList)
                        .handler(request -> collection.list(media, request))
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .args(MediaSchema.createArgs(media))
                        .permission(this::mayCreate)
                        .handler(request -> create(media, request))
                        .allowBatch(false)
                        .build())
                .register();
        routes.route(media.namespace(), "/" + media.base() + "/(?P<id>[\\d]+)")
                .arg(PostSchema.idArg())
                .schema(() -> schema(media))
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(media.schema()))
                        .permission(this::mayRead)
                        .handler(request -> item(media, request))
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(MediaSchema.updateArgs(media, this::storedWhileChecking))
                        .permission(request -> mayUpdate(media, request))
                        .handler(request -> update(media, request))
                        .allowBatch(false)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(PostSchema.forceArg())
                        .permission(this::mayDelete)
                        .handler(request -> delete(media, request))
                        .allowBatch(false)
                        .build())
                .register();
        routes.route(media.namespace(), "/" + media.base() + "/(?P<id>[\\d]+)/post-process")
                .endpoint(Endpoint.of("POST")
                        .arg(MediaSchema.postProcessIdArg())
                        .arg(MediaSchema.actionArg())
                        .permission(request -> mayUpdate(media, request))
                        .handler(request -> postProcess(media, request))
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

    /** The caller's upload types on the request's site; none without a site or while its media feature is off. */
    private Optional<List<Map.Entry<String, String>>> allowedTypes() {
        if (!site.isResolved() || !site.features().isEnabled(MediaFeature.KEY)) {
            return Optional.empty();
        }
        return Optional.of(library.allowedTypes());
    }

    /**
     * WordPress's collection check: the edit context needs the attachment type's {@code edit_posts}. A site whose
     * media feature is off answers as no route would.
     */
    private PermissionResult mayList(RestRequest request) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return PermissionResult.denied(noRoute());
        }
        if (request.isEditContext() && !permissions.canType(request.identity(), "edit_posts", TYPE)) {
            return PermissionResult.denied(RestError.of(
                    "rest_forbidden_context",
                    "Sorry, you are not allowed to edit posts in this post type.",
                    request.authCode()));
        }
        return PermissionResult.allowed();
    }

    /**
     * WordPress's create checks, in its order: no {@code id}; an author other than the caller needs the attachment
     * type's {@code edit_others_posts}; a truthy {@code sticky} (from any source) needs its {@code edit_others_posts}
     * and {@code publish_posts}; the type's {@code create_posts}, which is {@code upload_files}; a {@code post} must be
     * a post of the site the caller may edit; and the declared type of a multipart {@code file} part must not be an
     * image the server cannot make sizes for, when sizes are to be made. Each refusal takes the caller's status (401
     * or 403) unless it has its own. The collection's {@code Allow} follows from it. A site whose media feature is off
     * answers as no route would.
     */
    private PermissionResult mayCreate(RestRequest request) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return PermissionResult.denied(noRoute());
        }
        if (nonEmpty(request, "id").isPresent()) {
            return PermissionResult.denied(RestError.of("rest_post_exists", "Cannot create existing post.", 400));
        }
        SecurityIdentity caller = request.identity();
        if (PostUpdateChecks.otherAuthor(request) && !permissions.canType(caller, "edit_others_posts", TYPE)) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit_others",
                    "Sorry, you are not allowed to create posts as this user.",
                    request.authCode()));
        }
        if (nonEmpty(request, "sticky").isPresent()
                && !(permissions.canType(caller, "edit_others_posts", TYPE)
                        && permissions.canType(caller, "publish_posts", TYPE))) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_assign_sticky",
                    "Sorry, you are not allowed to make posts sticky.",
                    request.authCode()));
        }
        if (!permissions.canType(caller, "create_posts", TYPE)) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_create",
                    "Sorry, you are not allowed to create posts as this user.",
                    request.authCode()));
        }
        Optional<JsonNode> post = nonEmpty(request, "post");
        if (post.isPresent() && !mayEditParent(caller, PhpValues.toLong(post.get()))) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit",
                    "Sorry, you are not allowed to upload media to this post.",
                    request.authCode()));
        }
        // a part sent without a file has no declared type, as PHP records none for it
        Optional<String> declared = MediaUploadRequest.filePart(request)
                .filter(UploadedFile::uploaded)
                .flatMap(UploadedFile::contentType);
        if (declared.isPresent() && library.refusesImageType(declared.get(), generateSubSizes(request))) {
            return PermissionResult.denied(RestError.of(
                    "rest_upload_image_type_not_supported",
                    "The web server cannot generate responsive image sizes for this image. Convert it to JPEG or PNG"
                            + " before uploading.",
                    400));
        }
        return PermissionResult.allowed();
    }

    /** Whether the caller may edit the post {@code id} of this site; a post that does not exist cannot be. */
    private boolean mayEditParent(SecurityIdentity caller, long id) {
        return id > 0
                && reads.post(PostId.of(id))
                        .map(parent -> permissions.can(caller, "edit_post", parent))
                        .orElse(false);
    }

    /**
     * WordPress's create: a {@code post} that is a revision or an attachment is refused, then the file is received
     * ({@link MediaUploadRequest#receive}), its refusals in WordPress's order, then stored and answered ({@link
     * MediaWriter#create}), which deletes a spooled body whatever the outcome.
     */
    private RestResponse create(PostRoutes media, RestRequest request) {
        refuseParentType(request);
        MediaUploadRequest.Body body;
        try {
            body = MediaUploadRequest.receive(request, library);
        } catch (MediaUploadRequest.NestedUploadError nested) {
            return nested.answer();
        }
        return writer.create(media, request, body);
    }

    /**
     * WordPress's update checks, the posts routes' own ({@link PostUpdateChecks}): the attachment must exist on the
     * site, the caller needs {@code edit_post} on it, then another author, stickiness and the named terms. Neither the
     * upload capability nor the new parent is checked. The attachment and the posts it inherits its status from are
     * read through this request's reads, so a listed attachment's links cost no further read. A site whose media
     * feature is off answers as no route would.
     */
    private PermissionResult mayUpdate(PostRoutes media, RestRequest request) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return PermissionResult.denied(noRoute());
        }
        return updateChecks.update(
                media, request, reads.attachment(request).map(MediaSnapshot::post), may(request, "edit_post"));
    }

    /**
     * WordPress's delete checks, the posts routes' own: the attachment must exist on the site and the caller needs
     * {@code delete_post} on it. A site whose media feature is off answers as no route would.
     */
    private PermissionResult mayDelete(RestRequest request) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return PermissionResult.denied(noRoute());
        }
        return PostsController.deleteChecks(
                request, reads.attachment(request).map(MediaSnapshot::post), may(request, "delete_post"));
    }

    /**
     * Whether the caller has the post capability {@code capability} on an attachment, decided with the posts it
     * inherits its status from read through this request's reads, so a listed attachment's links cost no further read.
     */
    private Predicate<PostSnapshot> may(RestRequest request, String capability) {
        return post -> capabilities.can(
                request.identity(),
                capability,
                Optional.of(PostCapabilityFacts.facts(post, types, statuses, reads::post)));
    }

    /**
     * The stored attachment of the request whose update arguments are being checked, read through this request's
     * reads, for the status and template checks, which accept the stored value; none when there is no such attachment
     * or it cannot be read (the permission check then answers for it).
     */
    private Optional<PostSnapshot> storedWhileChecking() {
        Optional<RestRequest> request = ArgChecks.checking();
        if (request.isEmpty()) {
            return Optional.empty();
        }
        try {
            return reads.attachment(request.get()).map(MediaSnapshot::post);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * WordPress's update: a {@code post} that is a revision or an attachment (the attachment itself included) is
     * refused before anything else, then the attachment is written and answered ({@link MediaWriter#update}). A file
     * or a raw body sent along is never read.
     */
    private RestResponse update(PostRoutes media, RestRequest request) {
        MediaSnapshot stored = reads.attachment(request).orElseThrow(() -> new RestException(invalidId()));
        refuseParentType(request);
        return writer.update(media, stored, request);
    }

    /**
     * WordPress's delete with media trash off (its default): without {@code force} the attachment cannot be trashed
     * (501); with it, the attachment is rendered in the edit context, whatever context was asked for, then deleted for
     * good with every file of it, and the answer is {@code {"deleted": true, "previous": <that rendering without
     * links>}}. The request's later reads (its {@code Allow}) no longer find it.
     */
    private RestResponse delete(PostRoutes media, RestRequest request) {
        MediaSnapshot stored = reads.attachment(request).orElseThrow(() -> new RestException(invalidId()));
        if (!request.param("force").map(PhpValues::toBoolean).orElse(false)) {
            throw new RestException(RestError.of(
                    "rest_trash_not_supported",
                    "The post does not support trashing. Set 'force=true' to delete.",
                    501));
        }
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), media.schema(), "edit", request.hasParam("_embed"));
        JsonNode previous = fields.prepare(media, stored, selection, "edit", request, new AttachmentMarkup.Counters())
                .body();
        PostId id = stored.post().id();
        if (library.delete(id).isEmpty()) {
            throw new RestException(RestError.of("rest_cannot_delete", "The post cannot be deleted.", 500));
        }
        reads.forget(id);
        ObjectNode body = RestJson.NODES.objectNode();
        body.put("deleted", true);
        body.set("previous", previous);
        return RestResponse.ok(body);
    }

    /**
     * WordPress's post-process: the attachment's missing sizes are made (one without metadata yet, or whose processing
     * failed, from scratch), outside any transaction. A failure while making them is logged and ignored, as the
     * client calls again; the answer is the attachment read again, 200 in the edit context, in every state. An
     * attachment deleted meanwhile is answered as one that does not exist.
     */
    private RestResponse postProcess(PostRoutes media, RestRequest request) {
        MediaSnapshot stored = reads.attachment(request).orElseThrow(() -> new RestException(invalidId()));
        PostId id = stored.post().id();
        try {
            library.process(id);
        } catch (SiteFeatureDisabledException off) {
            throw off;
        } catch (PostException refused) {
            // no media row (an attachment of the posts module alone), or deleted since the permission passed
            LOG.infof("the post-process of attachment %d was refused: %s", id.value(), refused.getMessage());
        } catch (RuntimeException | Error failure) {
            LOG.errorf(failure, "the post-process of attachment %d failed", id.value());
        }
        Optional<MediaSnapshot> reread = library.read(id);
        if (reread.isEmpty() && !stored.storageKey().isEmpty()) {
            reads.forget(id);
            throw new RestException(invalidId());
        }
        // an attachment without a stored file has nothing to process: it is answered as it was read
        MediaSnapshot processed = reread.orElse(stored);
        reads.remember(processed);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), media.schema(), "edit", request.hasParam("_embed"));
        return fields.prepare(media, processed, selection, "edit", request, new AttachmentMarkup.Counters());
    }

    /** The refusal of a {@code post} that names a revision or an attachment of the site. */
    private void refuseParentType(RestRequest request) {
        long id = request.param("post").map(PhpValues::toLong).orElse(0L);
        if (id > 0) {
            Optional<PostSnapshot> parent = reads.post(PostId.of(id));
            if (parent.isPresent()
                    && (parent.get().type().equals("revision")
                            || parent.get().type().equals(TYPE))) {
                throw new RestException(RestError.of("rest_invalid_param", "Invalid parent type.", 400));
            }
        }
    }

    /**
     * Whether the image check counts on sizes being made, as WordPress reads the request's {@code generate_sub_sizes}
     * there: absent, sizes are made; sent, only the boolean false (a sanitized value) asks for none. The check also
     * runs for the {@code Allow} of a request its arguments refused, which was never sanitized: the text
     * {@code "false"} or {@code "0"} then still counts as sizes to make.
     */
    private static boolean generateSubSizes(RestRequest request) {
        return request.param("generate_sub_sizes")
                .map(value -> !(value.isBoolean() && !value.booleanValue()))
                .orElse(true);
    }

    /** The parameter {@code name} from any source, when it is not empty in PHP's sense. */
    private static Optional<JsonNode> nonEmpty(RestRequest request, String name) {
        return request.param(name).filter(value -> !PhpValues.isEmpty(value));
    }

    /**
     * WordPress's item read checks, in its order: the attachment must exist on the site; the edit context needs
     * {@code edit_post}; a {@code password} in the query string never matches; the attachment must be readable. A site
     * whose media feature is off answers as no route would.
     */
    private PermissionResult mayRead(RestRequest request) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            return PermissionResult.denied(noRoute());
        }
        Optional<MediaSnapshot> found = reads.attachment(request);
        if (found.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        PostSnapshot post = found.get().post();
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
        if (!permissions.restReadable(caller, post, reads::post)) {
            return PermissionResult.forbidden(request);
        }
        return PermissionResult.allowed();
    }

    /** The attachment in the request's context, with the {@code Link} to its page. */
    private RestResponse item(PostRoutes media, RestRequest request) {
        MediaSnapshot attachment = reads.attachment(request).orElseThrow(() -> new RestException(invalidId()));
        Fields.Selection selection = Fields.forResponse(request, media.schema());
        RestResponse response = fields.prepare(
                media, attachment, selection, request.context(), request, new AttachmentMarkup.Counters());
        return response.withHeader(
                "Link",
                "<" + postFields.link(media.type(), attachment.post()) + ">; rel=\"alternate\"; type=text/html");
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

    private static RestError noRoute() {
        return RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404);
    }
}
