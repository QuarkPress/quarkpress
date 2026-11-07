package org.quarkpress.it.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaQuery;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.runtime.ImageMetaReader;
import org.quarkpress.media.runtime.TikaTypes;
import org.quarkpress.posts.PostSnapshot;

/**
 * Test scaffolding, not a product API (the REST media routes come later): the media library of the site the request
 * is bound to, through the media module's Java service, as the caller. {@code /it/media} acts on the main site,
 * {@code /<site>/it/media} ({@link ItSiteMediaResource}) on a site in a subdirectory. Uploads and post-processing run
 * outside any transaction, as the service requires: processing commits each save on its own. Refusals are answered
 * by {@link ItRefusals}.
 */
@Path("/it/media")
@Produces(MediaType.APPLICATION_JSON)
public class ItMediaResource {

    private static final DateTimeFormatter MYSQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** WordPress's empty date, which a post without a publication date holds in {@code post_date_gmt}. */
    private static final String ZERO_DATE = "0000-00-00 00:00:00";

    /**
     * The fields of an attachment update; an absent one keeps its value. {@code post} is the parent's id (0 detaches).
     */
    public record UpdateRequest(
            String title,
            String caption,
            String description,
            @JsonProperty("alt_text") String altText,
            Long post,
            String status,
            Long author) {}

    @Inject
    Media media;

    @Inject
    ImageProcessor processor;

    @Inject
    ActiveStorage storage;

    @Inject
    SiteContext site;

    /**
     * Uploads the {@code file} part with its declared type and the text fields WordPress's REST upload takes; answers
     * {@code 201} with the attachment, processed (the processing mode is {@code sync}).
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public RestResponse<MediaView> upload(
            @RestForm("file") FileUpload file,
            @RestForm("title") String title,
            @RestForm("caption") String caption,
            @RestForm("description") String description,
            @RestForm("alt_text") String altText,
            @RestForm("post") String post,
            @RestForm("status") String status,
            @RestForm("generate_sub_sizes") String generateSubSizes,
            @RestForm("convert_format") String convertFormat) {
        if (file == null) {
            throw new BadRequestException("a file part is required");
        }
        Upload.Builder upload = Upload.builder(file.fileName(), file.uploadedFile());
        if (file.contentType() != null && !file.contentType().isBlank()) {
            upload.declaredType(file.contentType());
        }
        if (title != null) {
            upload.title(title);
        }
        if (caption != null) {
            upload.caption(caption);
        }
        if (description != null) {
            upload.description(description);
        }
        if (altText != null) {
            upload.altText(altText);
        }
        if (post != null && !post.isBlank()) {
            upload.parent(PostId.of(postId(post)));
        }
        if (status != null) {
            upload.status(status);
        }
        if (generateSubSizes != null) {
            upload.generateSubSizes(Boolean.parseBoolean(generateSubSizes.trim()));
        }
        if (convertFormat != null) {
            upload.convertFormat(Boolean.parseBoolean(convertFormat.trim()));
        }
        Upload built = upload.build();
        MediaSnapshot stored = outsideTransaction(() -> media.upload(built));
        return RestResponse.status(RestResponse.Status.CREATED, MediaView.of(stored));
    }

    /** The attachment; 404 when it is no attachment of the site. */
    @GET
    @Path("/{id}")
    public MediaView find(@PathParam("id") long id) {
        return MediaView.of(found(id));
    }

    /** Updates the attachment with the fields given; answers it. */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public MediaView update(@PathParam("id") long id, UpdateRequest request) {
        MediaChanges.Builder changes = MediaChanges.builder();
        if (request.title() != null) {
            changes.title(request.title());
        }
        if (request.caption() != null) {
            changes.caption(request.caption());
        }
        if (request.description() != null) {
            changes.description(request.description());
        }
        if (request.altText() != null) {
            changes.altText(request.altText());
        }
        if (request.post() != null) {
            changes.parent(request.post());
        }
        if (request.status() != null) {
            changes.status(request.status());
        }
        if (request.author() != null) {
            changes.author(UserId.of(request.author()));
        }
        return MediaView.of(media.update(PostId.of(id), changes.build()));
    }

    /** Deletes the attachment for good, its files once the delete has committed; answers it as it was. */
    @DELETE
    @Path("/{id}")
    public MediaView delete(@PathParam("id") long id) {
        return media.delete(PostId.of(id)).map(MediaView::of).orElseThrow(NotFoundException::new);
    }

    /** WordPress's post-process of the attachment: makes what is missing; answers it. */
    @POST
    @Path("/{id}/process")
    public MediaView process(@PathParam("id") long id) {
        return MediaView.of(outsideTransaction(() -> media.process(PostId.of(id))));
    }

    /**
     * The site's attachments matching the arguments (each optional): {@code mime_type} (repeatable), {@code parent}
     * (0 for the unattached ones) and {@code author}; at most 100, as the posts module orders them by default.
     */
    @GET
    public List<MediaView> query(
            @QueryParam("mime_type") List<String> mimeTypes,
            @QueryParam("parent") Long parent,
            @QueryParam("author") Long author) {
        MediaQuery.Builder query = MediaQuery.builder().perPage(100);
        if (mimeTypes != null && !mimeTypes.isEmpty()) {
            query.mimeTypes(mimeTypes);
        }
        if (parent != null) {
            query.parent(parent);
        }
        if (author != null) {
            query.author(UserId.of(author));
        }
        return media.query(query.build()).items().stream().map(MediaView::of).toList();
    }

    /**
     * The attachment as WordPress's tables hold it: {@code post}, the row with WordPress's column names (dates as
     * {@code yyyy-MM-dd HH:mm:ss}, local in the site's zone and in UTC); {@code meta}, {@code _wp_attached_file},
     * {@code _wp_attachment_metadata} and, when there is one, {@code _wp_attachment_image_alt}; {@code files}, the
     * sorted names in the attachment's folder.
     */
    @GET
    @Path("/{id}/wordpress")
    public ObjectNode wordPress(@PathParam("id") long id) {
        MediaSnapshot attachment = found(id);
        PostSnapshot post = attachment.post();
        ZoneId zone = site.zone();
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        ObjectNode row = answer.putObject("post");
        row.put("ID", post.id().value());
        row.put("post_author", post.author().map(UserId::value).orElse(0L));
        row.put("post_date", MYSQL_DATE.format(post.localDate(zone)));
        row.put("post_date_gmt", post.publishedAt().map(ItMediaResource::utc).orElse(ZERO_DATE));
        row.put("post_content", post.content());
        row.put("post_title", post.title());
        row.put("post_excerpt", post.excerpt());
        row.put("post_status", post.status());
        row.put("comment_status", post.commentStatus());
        row.put("ping_status", post.pingStatus());
        row.put("post_password", post.password());
        row.put("post_name", post.slug());
        row.put("post_modified", MYSQL_DATE.format(post.localModified(zone)));
        row.put("post_modified_gmt", utc(post.modifiedAt()));
        row.put("post_parent", post.parent().map(PostId::value).orElse(0L));
        row.put("guid", post.guid());
        row.put("menu_order", post.menuOrder());
        row.put("post_type", post.type());
        row.put("post_mime_type", attachment.mimeType());
        ObjectNode meta = answer.putObject("meta");
        meta.put("_wp_attached_file", attachment.attachedFile());
        meta.set("_wp_attachment_metadata", attachment.wordPressMetadata());
        if (!attachment.altText().isEmpty()) {
            meta.put("_wp_attachment_image_alt", attachment.altText());
        }
        String key = attachment.storageKey();
        int slash = key.lastIndexOf('/');
        List<String> files = new ArrayList<>(storage.storage().list(slash < 0 ? "" : key.substring(0, slash)));
        files.sort(null);
        files.forEach(answer.putArray("files")::add);
        return answer;
    }

    /** The class names of the registered ImageIO reader providers, to see which plugins a native image found. */
    @GET
    @Path("/readers")
    public List<String> readers() {
        List<String> names = new ArrayList<>();
        Iterator<ImageReaderSpi> providers =
                IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        providers.forEachRemaining(spi -> names.add(spi.getClass().getName()));
        return names;
    }

    /**
     * What the media module reads from the posted bytes: {@code detected}, Tika's content type as the type check sees
     * it; {@code image}, the processor's type and stored dimensions, or null; {@code imageMeta}, the embedded data of
     * an image the processor reads, or null.
     */
    @POST
    @Path("/inspect")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public ObjectNode inspect(byte[] bytes) throws IOException {
        java.nio.file.Path file = Files.createTempFile("it-media-inspect", ".bin");
        try {
            Files.write(file, bytes);
            ObjectNode answer = JsonNodeFactory.instance.objectNode();
            answer.put("detected", TikaTypes.detect(file));
            Optional<ImageInfo> image = processor.read(file);
            if (image.isPresent()) {
                answer.putObject("image")
                        .put("mimeType", image.get().mimeType())
                        .put("width", image.get().width())
                        .put("height", image.get().height());
                answer.set("imageMeta", ImageMetaReader.read(file, image.get().mimeType(), "en_US"));
            } else {
                answer.putNull("image");
                answer.putNull("imageMeta");
            }
            return answer;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** The {@code post} field as a post id; a text that is no positive number is a bad request. */
    private static long postId(String text) {
        try {
            long id = Long.parseLong(text.trim());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException notANumber) {
            // answered below
        }
        throw new BadRequestException("the post field is not a post id: " + text);
    }

    private MediaSnapshot found(long id) {
        return media.find(PostId.of(id)).orElseThrow(NotFoundException::new);
    }

    private static String utc(Instant at) {
        return MYSQL_DATE.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    /** Runs {@code work} with any transaction of the caller suspended, rethrowing its own exception. */
    private static <T> T outsideTransaction(Callable<T> work) {
        try {
            return QuarkusTransaction.suspendingExisting().call(work);
        } catch (QuarkusTransactionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw wrapped;
        }
    }
}
