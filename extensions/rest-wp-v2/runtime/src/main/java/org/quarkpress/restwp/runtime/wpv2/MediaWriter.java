package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostDates;
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
import org.quarkpress.security.SystemIdentity;
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
 *
 * <p>An update prepares the same fields against the stored attachment, and answers the preparation's errors before
 * anything is written. A parent must be a post of the site (0 detaches). The status is kept or changed as on a create:
 * {@code private} as asked, any other accepted status {@code inherit}. The attachment, its featured image (audio and
 * video only, never refused), its template and its meta are written in one transaction, which any refusal rolls back
 * whole; the answer is 200 with the attachment in the edit context. The file never changes.
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

    @Inject
    PageTemplates templates;

    @Inject
    QuarkPressClock clock;

    @Inject
    SystemIdentity system;

    @Inject
    CurrentIdentityAssociation association;

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
        return answer(routes, request, uploaded)
                .withHeader(
                        ATTACHMENT_ID_HEADER, Long.toString(uploaded.post().id().value()));
    }

    /** The 201 without the id header: the attachment in the edit context with its address. */
    private RestResponse answer(PostRoutes routes, RestRequest request, MediaSnapshot uploaded) {
        reads.remember(uploaded);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        PostId id = uploaded.post().id();
        return fields.prepare(routes, uploaded, selection, "edit", request, new AttachmentMarkup.Counters())
                .withStatus(201)
                .withHeader("Location", urls.route(routes.item(id)));
    }

    /**
     * Stores a sideloaded upload (a file fetched from a URL) and answers it as WordPress's sideload does: no request
     * field but the upload's own is written, no featured image or meta, and the 201 carries {@code Location} but no
     * upload header. A refusal of the pipeline is {@code upload_error} (500); the network's limits and the parent
     * answer as for any upload. Must be called with no transaction active.
     */
    RestResponse sideload(PostRoutes routes, RestRequest request, Upload upload) {
        MediaSnapshot uploaded;
        try {
            uploaded = media.upload(upload);
        } catch (MediaException refused) {
            throw MediaUploadRequest.pipelineRefusal(refused, "upload_error", request.authCode());
        } catch (PostException refused) {
            throw new RestException(PostWriter.insertError(refused));
        }
        return answer(routes, request, uploaded);
    }

    // ---- an update

    /**
     * Updates the attachment {@code stored} with the request's fields and answers it in the edit context; see the
     * class description. The parent's type has been checked; a file or a raw body sent along is never read.
     */
    RestResponse update(PostRoutes routes, MediaSnapshot stored, RestRequest request) {
        PostRequest prepared = PostRequest.prepare(routes, request, Optional.of(stored.post()), services());
        MediaChanges changes = changes(prepared, request);
        PostId id = stored.post().id();
        QuarkusTransaction.joiningExisting().call(() -> {
            write(routes, stored, changes, prepared, request);
            return null;
        });
        MediaSnapshot updated = media.read(id).orElseThrow(() -> new RestException(MediaReads.invalidId()));
        reads.remember(updated);
        request.useContext("edit");
        Fields.Selection selection =
                Fields.forResponse(Fields.of(request), routes.schema(), "edit", request.hasParam("_embed"));
        return fields.prepare(routes, updated, selection, "edit", request, new AttachmentMarkup.Counters());
    }

    /**
     * The attachment fields of a prepared update: the post fields, the caption, the description, the parent (0
     * detaches; any other id must be a post of the site), only {@code private} as a status (any other status the
     * preparation accepted leaves the attachment {@code inherit}), the cleared dates as now, and the alternative text
     * when the request has it ({@code ""} clears it).
     */
    private MediaChanges changes(PostRequest prepared, RestRequest request) {
        PostChanges post = prepared.changes();
        MediaChanges.Builder changes = MediaChanges.builder();
        post.title().ifPresent(changes::title);
        text(request, "caption").ifPresent(changes::caption);
        text(request, "description").ifPresent(changes::description);
        Optional<Long> parent = request.param("post").map(PhpValues::toLong);
        if (parent.isPresent()) {
            long value = parent.get();
            if (value < 0 || (value > 0 && reads.post(PostId.of(value)).isEmpty())) {
                throw new RestException(RestError.of("rest_post_invalid_id", "Invalid post parent ID.", 400));
            }
            changes.parent(value);
        }
        if (post.author().isPresent() && post.author().getAsLong() > 0) {
            changes.author(UserId.of(post.author().getAsLong()));
        }
        post.status().ifPresent(status -> changes.status(status.equals("private") ? "private" : "inherit"));
        if (prepared.datesCleared()) {
            // an attachment's date never floats: a cleared date is now
            Instant now = PostDates.now(clock);
            changes.date(LocalDateTime.ofInstant(now, site.zone())).dateGmt(now);
        } else {
            post.date().ifPresent(changes::date);
            post.dateGmt().ifPresent(changes::dateGmt);
        }
        post.slug().ifPresent(changes::slug);
        post.commentStatus().ifPresent(changes::commentStatus);
        post.pingStatus().ifPresent(changes::pingStatus);
        request.param("alt_text").map(PhpValues::toPhpString).ifPresent(changes::altText);
        return changes.build();
    }

    /**
     * Writes an update in WordPress's order, in the caller's transaction: the attachment, then its featured image
     * (audio and video only; an image ignores the value, and an id that names no attachment is ignored), its template
     * and its meta. Any refusal is thrown, which rolls everything back.
     */
    private void write(
            PostRoutes routes, MediaSnapshot stored, MediaChanges changes, PostRequest prepared, RestRequest request) {
        PostId id = stored.post().id();
        MediaSnapshot saved;
        try {
            saved = media.update(id, changes);
        } catch (PostException refused) {
            throw new RestException(PostWriter.insertError(refused));
        } catch (MediaException refused) {
            throw new RestException(RestError.of(refused.code(), refused.getMessage(), 400));
        }
        SecurityIdentity caller = association.getIdentity();
        association.setIdentity(system.identity());
        try {
            String type = saved.mimeType();
            if (prepared.featuredMedia().isPresent() && (type.startsWith("audio/") || type.startsWith("video/"))) {
                // whether the value changed is not used: WordPress ignores the answer on an update
                featuredImages.set(id, prepared.featuredMedia().getAsLong());
            }
            if (prepared.template().isPresent()) {
                templates.set(id, prepared.template().get());
            }
        } catch (PostException refused) {
            throw new RestException(PostWriter.insertError(refused));
        } finally {
            association.setIdentity(caller);
        }
        if (prepared.meta().isPresent()) {
            metaFields.write(routes, id, prepared.meta().get(), request).ifPresent(error -> {
                throw new RestException(error);
            });
        }
    }

    private PostRequest.Services services() {
        return new PostRequest.Services(permissions, statuses, posts, rows, profiles, site);
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
            return Optional.of(PostRequest.prepare(routes, request, Optional.empty(), services()));
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
                // written as the system, as an update writes it: the upload's permission check has run
                SecurityIdentity caller = association.getIdentity();
                association.setIdentity(system.identity());
                boolean set;
                try {
                    set = featuredImages.set(id, thumbnail);
                } finally {
                    association.setIdentity(caller);
                }
                if (thumbnail != 0 && !set) {
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
     * Deletes a spooled body; a failure is logged and never replaces the answer (a file-system storage's start-up
     * sweep removes the file later; the default temporary folder has no sweep).
     */
    private static void deleteSpool(Path spooled) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException | RuntimeException failure) {
            LOG.warnf(failure, "could not delete the spooled upload %s", spooled);
        }
    }
}
