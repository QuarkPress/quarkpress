package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizes;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.runtime.ContextFilter;

/**
 * An attachment's fields as WordPress's attachments controller prepares them: the posts controller's fields, then
 * the attachment's own in WordPress's order, each computed only when its top-level name is selected (a nested
 * selection such as {@code description.raw} computes nothing), all filtered by the context.
 *
 * <p>File URLs (the source URL, the sizes' URLs and those inside the rendered description and caption) follow the
 * request's scheme, switched to {@code https} for a secure request; the stored guid keeps its own. The rendered
 * description is WordPress's attachment markup followed by the stored description through the registered content
 * filters; the rendered caption is the stored caption through the registered excerpt filters or, when it is empty,
 * the text generated from that markup and the description. An attachment whose metadata is still being made or could
 * not be made shows as one without metadata: no dimensions, no sizes. An attachment post without a stored file (one
 * written past the media module) answers with its guid as its file URL and no file details. The attachment's links
 * come with the fields when the links or the embeds are selected.
 */
@ApplicationScoped
public class MediaFields {

    /** The sizes WordPress names missing for a PDF without previews, when they are registered. */
    private static final List<String> PDF_FALLBACK_SIZES = List.of("thumbnail", "medium", "large");

    private static final String WEBP = "image/webp";

    private static final int WEBP_QUALITY = 86;

    private static final int DEFAULT_QUALITY = 82;

    @Inject
    PostFields posts;

    @Inject
    Media media;

    @Inject
    ImageSizes imageSizes;

    @Inject
    MediaReads reads;

    @Inject
    MediaLinks links;

    /**
     * The attachment's selected fields in WordPress's order, filtered by {@code context}. The audio and video players
     * of the rendered description and of a generated caption take their numbers from {@code counters}, the
     * description's first, so one instance serves every attachment of a response. The links come with them when
     * {@code _links} or {@code _embedded} is selected.
     */
    RestResponse prepare(
            PostRoutes routes,
            MediaSnapshot attachment,
            Fields.Selection selection,
            String context,
            RestRequest request,
            AttachmentMarkup.Counters counters) {
        PostSnapshot post = attachment.post();
        ObjectNode data = posts.fields(routes, post, selection, context, request, false);
        boolean secure = request.secure();
        boolean stored = !attachment.attachedFile().isEmpty();
        String fileUrl = scheme(stored ? attachment.url() : post.guid(), secure);
        boolean image = stored && attachment.mimeType().startsWith("image/");
        Markup markup = new Markup(attachment, fileUrl, secure, counters);
        if (selection.includesExactly("description")) {
            ObjectNode description = data.putObject("description");
            description.put("raw", post.content());
            description.put(
                    "rendered",
                    AttachmentMarkup.description(markup.prepended(), posts.renderContent(post, post.content())));
        }
        if (selection.includesExactly("caption")) {
            ObjectNode caption = data.putObject("caption");
            caption.put("raw", post.excerpt());
            String text = post.excerpt().isEmpty()
                    ? AttachmentMarkup.generatedCaption(markup.prepended(), posts.renderContent(post, post.content()))
                    : post.excerpt();
            caption.put("rendered", posts.renderExcerpt(post, text));
        }
        if (selection.includesExactly("alt_text")) {
            data.put("alt_text", attachment.altText());
        }
        if (selection.includesExactly("media_type")) {
            data.put("media_type", image ? "image" : "file");
        }
        if (selection.includesExactly("mime_type")) {
            data.put("mime_type", attachment.mimeType());
        }
        if (selection.includesExactly("media_details")) {
            data.set("media_details", MediaDetails.of(attachment, fileUrl, secure));
        }
        if (selection.includesExactly("post")) {
            Optional<PostId> parent = post.parent();
            if (parent.isPresent()) {
                data.put("post", parent.get().value());
            } else {
                data.putNull("post");
            }
        }
        if (selection.includesExactly("source_url")) {
            data.put("source_url", fileUrl);
        }
        if (selection.includesExactly("missing_image_sizes")) {
            ArrayNode missing = data.putArray("missing_image_sizes");
            missingSizes(attachment, image).forEach(missing::add);
        }
        if (selection.includesExactly("filename")) {
            Optional<String> name = fileName(attachment, image);
            if (name.isPresent()) {
                data.put("filename", name.get());
            } else {
                data.putNull("filename");
            }
        }
        if (selection.includesExactly("filesize")) {
            OptionalLong size = fileSize(attachment);
            if (size.isPresent()) {
                data.put("filesize", size.getAsLong());
            } else {
                data.putNull("filesize");
            }
        }
        if (image) {
            if (selection.includesExactly("exif_orientation")) {
                data.put("exif_orientation", orientation(attachment));
            }
            if (selection.includesExactly("image_output_format")) {
                Optional<String> output = media.outputType(attachment.mimeType());
                if (output.isPresent()) {
                    data.put("image_output_format", output.get());
                } else {
                    data.putNull("image_output_format");
                }
            }
            if (selection.includesExactly("image_save_progressive")) {
                data.put("image_save_progressive", false);
            }
            if (selection.includesExactly("image_quality")) {
                ObjectNode quality = data.putObject("image_quality");
                quality.put("default", attachment.mimeType().equals(WEBP) ? WEBP_QUALITY : DEFAULT_QUALITY);
                quality.putArray("sizes");
            }
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, routes.schema(), context));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            response = response.withLinks(links.of(routes, attachment, request));
        }
        return response;
    }

    /**
     * The registered sizes the attachment lacks: the media module's answer for an image; for a PDF, the fallback sizes
     * that are registered and not stored; none otherwise.
     */
    private List<String> missingSizes(MediaSnapshot attachment, boolean image) {
        if (image) {
            return media.missingSizes(attachment.post().id());
        }
        if (!attachment.mimeType().equals("application/pdf")) {
            return List.of();
        }
        Set<String> registered = imageSizes.registered().keySet();
        List<String> missing = new ArrayList<>();
        for (String name : PDF_FALLBACK_SIZES) {
            if (registered.contains(name) && !attachment.sizes().containsKey(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * The name of the untouched upload for an image whose main file is a copy of it, else the main file's name; none
     * without a stored file.
     */
    private static Optional<String> fileName(MediaSnapshot attachment, boolean image) {
        if (image && attachment.originalImage().isPresent()) {
            return Optional.of(MediaDetails.baseName(attachment.originalImage().get()));
        }
        if (attachment.attachedFile().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MediaDetails.baseName(attachment.attachedFile()));
    }

    /**
     * The metadata's file size when it is a positive number, else the stored file's size when it is known; none
     * without a stored file.
     */
    private static OptionalLong fileSize(MediaSnapshot attachment) {
        if (attachment.attachedFile().isEmpty()) {
            return OptionalLong.empty();
        }
        if (!withoutMetadata(attachment)) {
            JsonNode recorded = attachment.wordPressMetadata().get("filesize");
            if (recorded != null && recorded.isNumber() && recorded.asLong() > 0) {
                return OptionalLong.of(recorded.asLong());
            }
        }
        return attachment.fileSize() > 0 ? OptionalLong.of(attachment.fileSize()) : OptionalLong.empty();
    }

    /** The orientation of the image's embedded data when it is positive, else 1. */
    private static int orientation(MediaSnapshot attachment) {
        JsonNode value =
                attachment.imageMeta().map(meta -> meta.get("orientation")).orElse(null);
        if (value == null || value.isNull()) {
            return 1;
        }
        long orientation;
        if (value.isNumber()) {
            orientation = value.asLong();
        } else {
            orientation = leadingInteger(value.asText());
        }
        return orientation > 0 && orientation <= Integer.MAX_VALUE ? (int) orientation : 1;
    }

    /** PHP's integer reading of a text: its leading digits after an optional sign, 0 without any. */
    private static long leadingInteger(String text) {
        int i = 0;
        boolean negative = false;
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            negative = text.charAt(i) == '-';
            i++;
        }
        long value = 0;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9' && value < Integer.MAX_VALUE) {
            value = value * 10 + (text.charAt(i) - '0');
            i++;
        }
        return negative ? -value : value;
    }

    /** Whether the attachment has no metadata yet, or none could be made. */
    private static boolean withoutMetadata(MediaSnapshot attachment) {
        return attachment.processing() == Processing.PENDING || attachment.processing() == Processing.FAILED;
    }

    /** {@code url} with {@code https} in place of {@code http} for a secure request. */
    static String scheme(String url, boolean secure) {
        return secure && url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
    }

    /**
     * The attachment's prepended markup, built anew for each field that needs it with the next player numbers, as
     * WordPress builds it for the description and again, hidden, for a generated caption.
     */
    private final class Markup {

        private final MediaSnapshot attachment;
        private final String fileUrl;
        private final boolean secure;
        private final AttachmentMarkup.Counters counters;
        private AttachmentMarkup.Attachment mapped;

        Markup(MediaSnapshot attachment, String fileUrl, boolean secure, AttachmentMarkup.Counters counters) {
            this.attachment = attachment;
            this.fileUrl = fileUrl;
            this.secure = secure;
            this.counters = counters;
        }

        String prepended() {
            ImageSizeDefinition medium = imageSizes.registered().get("medium");
            int width = medium == null ? 0 : medium.width();
            int height = medium == null ? 0 : medium.height();
            return AttachmentMarkup.prepended(mapped(), width, height, counters);
        }

        private AttachmentMarkup.Attachment mapped() {
            if (mapped == null) {
                mapped = map();
            }
            return mapped;
        }

        /**
         * The markup's view of the attachment: without metadata (being made, failed, or no stored file) it has no
         * dimensions and no sizes; a video's cover image gives its poster.
         */
        private AttachmentMarkup.Attachment map() {
            PostSnapshot post = attachment.post();
            boolean metadata =
                    !withoutMetadata(attachment) && !attachment.attachedFile().isEmpty();
            OptionalInt width = OptionalInt.empty();
            OptionalInt height = OptionalInt.empty();
            List<AttachmentMarkup.Size> sizes = new ArrayList<>();
            if (metadata) {
                ObjectNode stored = attachment.wordPressMetadata();
                width = dimension(stored.get("width"));
                height = dimension(stored.get("height"));
                for (Map.Entry<String, MediaSize> size : attachment.sizes().entrySet()) {
                    sizes.add(new AttachmentMarkup.Size(
                            size.getKey(),
                            size.getValue().file(),
                            size.getValue().width(),
                            size.getValue().height(),
                            scheme(attachment.url(size.getKey()), secure)));
                }
            }
            String name = attachment.attachedFile().isEmpty() ? "" : MediaDetails.baseName(attachment.attachedFile());
            return new AttachmentMarkup.Attachment(
                    post.id().value(),
                    attachment.mimeType(),
                    fileUrl,
                    post.title(),
                    name,
                    attachment.altText(),
                    width,
                    height,
                    sizes,
                    poster(post));
        }

        /**
         * The file URL of a video's cover image (its guid when the cover has no stored file), empty for anything else
         * or without one.
         */
        private String poster(PostSnapshot post) {
            if (!attachment.mimeType().startsWith("video/")) {
                return "";
            }
            long cover = FeaturedImages.of(post);
            if (cover <= 0) {
                return "";
            }
            return reads.attachment(PostId.of(cover))
                    .map(found ->
                            scheme(found.attachedFile().isEmpty() ? found.post().guid() : found.url(), secure))
                    .orElse("");
        }
    }

    private static OptionalInt dimension(JsonNode value) {
        return value != null && value.canConvertToInt() && value.isIntegralNumber()
                ? OptionalInt.of(value.intValue())
                : OptionalInt.empty();
    }
}
