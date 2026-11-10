package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.UploadedFile;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.security.UserProfiles;

/**
 * Stores a received upload as WordPress's REST upload does, in its order: the file and the attachment with the
 * request's post fields; then, after the row exists, its featured image and its registered meta; then the sizes; then
 * the answer, 201 with the attachment in the edit context, its {@code Location} and {@code X-WP-Upload-Attachment-ID}.
 *
 * <p>The post fields are prepared as the posts routes prepare them, plus the caption, the description and the parent
 * ({@code post}). When the preparation fails (an author that is no user, a status the caller may not set, an
 * {@code id} of 0, …), the upload goes on as if no post field had been sent, except the caption, which WordPress reads
 * with the file's own defaults, and the parent, which it keeps. Only {@code private} is stored as asked; any other status leaves the attachment
 * {@code inherit}, and a date never schedules it. A {@code template} is never stored on a create.
 *
 * <p>The featured image and the meta are written in a transaction of their own once the row is stored, outside any
 * transaction of the upload's. Their refusals answer the client as such, without the header, and leave the attachment
 * stored and unprocessed. Any other failure once the row is stored (making the sizes, writing those fields, rendering
 * the answer) is logged and answered 500 with the header, so that the client can finish the attachment through its
 * post-process route. A raw body's spooled file is deleted whatever the outcome.
 */
@ApplicationScoped
class MediaWriter {

    private static final Logger LOG = Logger.getLogger(MediaWriter.class);

    static final String ATTACHMENT_ID_HEADER = "X-WP-Upload-Attachment-ID";

    @Inject
    Media media;

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
    FeaturedImages featuredImages;

    @Inject
    MetaFields metaFields;

    @Inject
    MediaFields fields;

    @Inject
    MediaReads reads;

    @Inject
    RestUrls urls;

    /**
     * Stores the upload {@code body} with the request's fields and answers it; see the class description. Must be
     * called with no transaction active.
     */
    RestResponse create(PostRoutes routes, RestRequest request, MediaUploadRequest.Body body) {
        try {
            AfterInsert afterInsert = new AfterInsert(routes, request);
            try {
                MediaSnapshot uploaded = media.upload(upload(routes, request, body), afterInsert);
                return created(routes, request, uploaded);
            } catch (Throwable failure) {
                return failed(failure, afterInsert, body, request);
            }
        } finally {
            if (body instanceof MediaUploadRequest.Raw raw) {
                deleteSpool(raw.spooled());
            }
        }
    }

    /**
     * The answer to a failure: before the row was stored, the failure as it is (a refusal of the pipeline in
     * WordPress's words); a refusal of the fields written after the row, as it is; anything else after the row, a
     * logged 500 with the attachment's id in the header.
     */
    private RestResponse failed(
            Throwable failure, AfterInsert afterInsert, MediaUploadRequest.Body body, RestRequest request) {
        Optional<PostId> stored = afterInsert.stored();
        if (stored.isEmpty()) {
            if (failure instanceof MediaException refused) {
                throw MediaUploadRequest.pipelineRefusal(refused, body, request.authCode());
            }
            if (failure instanceof PostException refused) {
                throw new RestException(PostWriter.insertError(refused));
            }
            if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure);
        }
        if (afterInsert.refusal().filter(refusal -> refusal == failure).isPresent()) {
            throw (RestException) failure;
        }
        long id = stored.get().value();
        LOG.errorf(failure, "the upload of attachment %d failed after it was stored", id);
        RestError error =
                RestError.of("internal_server_error", "There has been a critical error on this website.", 500);
        return new RestResponse(
                500, error.toJson(), Map.of(ATTACHMENT_ID_HEADER, List.of(Long.toString(id))), Links.none());
    }

    /** The 201: the attachment in the edit context, whatever the request asked for, with its address and id. */
    private RestResponse created(PostRoutes routes, RestRequest request, MediaSnapshot uploaded) {
        reads.remember(uploaded);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        PostId id = uploaded.post().id();
        return fields.prepare(routes, uploaded, selection, "edit", request, new AttachmentMarkup.Counters())
                .withStatus(201)
                .withHeader("Location", urls.route(routes.item(id)))
                .withHeader(ATTACHMENT_ID_HEADER, Long.toString(id.value()));
    }

    // ---- the upload and its fields

    /** The upload of {@code body} with the request's fields. */
    private Upload upload(PostRoutes routes, RestRequest request, MediaUploadRequest.Body body) {
        Upload.Builder upload = switch (body) {
            case MediaUploadRequest.Multipart multipart -> {
                UploadedFile file = multipart.file();
                Upload.Builder builder = Upload.builder(file.fileName(), file.file());
                file.contentType().ifPresent(builder::declaredType);
                yield builder;
            }
            case MediaUploadRequest.Raw raw ->
                // the unsupported-image refusal applies to a multipart file part alone, and the title comes
                // from the stored name, not the one the header gave
                Upload.builder(raw.fileName(), raw.spooled())
                        .declaredType(raw.contentType())
                        .imageTypeCheck(false)
                        .clientNameTitle(false);
        };
        // read with the file's own defaults, so kept whatever happens to the other fields
        text(request, "caption").ifPresent(upload::caption);
        Optional<PostRequest> prepared = prepared(routes, request);
        if (prepared.isPresent()) {
            PostChanges changes = prepared.get().changes();
            changes.title().ifPresent(upload::title);
            changes.date().ifPresent(upload::date);
            changes.dateGmt().ifPresent(upload::dateGmt);
            changes.slug().ifPresent(upload::slug);
            changes.commentStatus().ifPresent(upload::commentStatus);
            changes.pingStatus().ifPresent(upload::pingStatus);
            if (changes.author().isPresent() && changes.author().getAsLong() > 0) {
                upload.author(UserId.of(changes.author().getAsLong()));
            }
            // any status but private leaves the attachment inherit
            changes.status().filter("private"::equals).ifPresent(upload::status);
            text(request, "description").ifPresent(upload::description);
        }
        // the parent is kept whatever happens to the post fields, as WordPress keeps it
        request.param("post")
                .map(PhpValues::toLong)
                .filter(parent -> parent > 0)
                .ifPresent(parent -> upload.parent(PostId.of(parent)));
        request.param("alt_text").map(PhpValues::toPhpString).ifPresent(upload::altText);
        upload.generateSubSizes(flag(request, "generate_sub_sizes"));
        upload.convertFormat(flag(request, "convert_format"));
        return upload.build();
    }

    /**
     * The request's post fields as the posts routes prepare them; empty when the preparation refuses them, which
     * WordPress's upload ignores.
     */
    private Optional<PostRequest> prepared(PostRoutes routes, RestRequest request) {
        try {
            return Optional.of(PostRequest.prepare(
                    routes,
                    request,
                    Optional.empty(),
                    new PostRequest.Services(permissions, statuses, posts, rows, profiles, site)));
        } catch (RestException ignored) {
            LOG.debugf(
                    "the post fields of an upload were dropped: %s",
                    ignored.error().code());
            return Optional.empty();
        }
    }

    /** A boolean argument, true unless the request says otherwise. */
    private static boolean flag(RestRequest request, String name) {
        return request.param(name).map(PhpValues::toBoolean).orElse(true);
    }

    /** A string, or an object's {@code raw} (empty included); nothing for any other value. */
    private static Optional<String> text(RestRequest request, String name) {
        Optional<JsonNode> value = request.param(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node = value.get();
        if (node.isObject()) {
            JsonNode raw = node.get("raw");
            return raw == null || raw.isNull() || raw.isContainerNode()
                    ? Optional.empty()
                    : Optional.of(PhpValues.toPhpString(raw));
        }
        return node.isContainerNode() ? Optional.empty() : Optional.of(PhpValues.toPhpString(node));
    }

    // ---- after the row

    /**
     * The fields WordPress writes once the attachment row exists, in its order: the featured image, then the meta. It
     * records the stored attachment's id first; a refusal is returned from the transaction and thrown once it ended,
     * so that it reaches the client unchanged.
     */
    private final class AfterInsert implements Media.AfterStore {

        private final PostRoutes routes;
        private final RestRequest request;
        private volatile PostId stored;
        private volatile RestException refusal;

        AfterInsert(PostRoutes routes, RestRequest request) {
            this.routes = routes;
            this.request = request;
        }

        Optional<PostId> stored() {
            return Optional.ofNullable(stored);
        }

        Optional<RestException> refusal() {
            return Optional.ofNullable(refusal);
        }

        @Override
        public void stored(MediaSnapshot attachment) {
            stored = attachment.post().id();
            Optional<RestError> refused = QuarkusTransaction.requiringNew().call(() -> write(attachment));
            if (refused.isPresent()) {
                refusal = new RestException(refused.get());
                throw refusal;
            }
        }

        private Optional<RestError> write(MediaSnapshot attachment) {
            PostId id = attachment.post().id();
            Optional<JsonNode> featured = request.param("featured_media");
            if (featured.isPresent()) {
                String type = attachment.mimeType();
                if (!(type.startsWith("audio/") || type.startsWith("video/"))) {
                    return Optional.of(RestError.of(
                            "rest_no_featured_media",
                            "This site does not support post thumbnails on attachments with MIME type " + type + ".",
                            400));
                }
                long thumbnail = PhpValues.toLong(featured.get());
                if (thumbnail == 0) {
                    featuredImages.set(id, 0);
                } else if (!featuredImages.set(id, thumbnail)) {
                    return Optional.of(RestError.of("rest_invalid_featured_media", "Invalid featured media ID.", 400));
                }
            }
            Optional<JsonNode> meta = request.param("meta");
            if (meta.isPresent()) {
                return metaFields.write(routes, id, meta.get(), request);
            }
            return Optional.empty();
        }
    }

    /**
     * Deletes a spooled body; a failure is logged and never replaces the answer (the storage's start-up sweep removes
     * the file later).
     */
    private static void deleteSpool(Path spooled) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException | RuntimeException failure) {
            LOG.warnf(failure, "could not delete the spooled upload %s", spooled);
        }
    }
}
