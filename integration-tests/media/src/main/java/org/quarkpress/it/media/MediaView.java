package org.quarkpress.it.media;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.MediaSnapshot;

/**
 * An attachment as the test resource answers it: the post's fields, the file side and WordPress's stored metadata.
 * Test scaffolding, not an API.
 *
 * @param sizes each generated size by name: {@code file}, {@code width}, {@code height}, {@code mimeType},
 *     {@code fileSize} and its public {@code url}
 * @param imageMeta the embedded data of an image, or null
 * @param metadata {@code _wp_attachment_metadata} as WordPress stores it
 */
public record MediaView(
        long id,
        String slug,
        String title,
        String caption,
        String description,
        String status,
        long author,
        long parent,
        String guid,
        String mimeType,
        String storageKey,
        String attachedFile,
        String originalImage,
        long fileSize,
        Integer width,
        Integer height,
        ObjectNode sizes,
        ObjectNode imageMeta,
        ObjectNode metadata,
        String altText,
        String processing,
        String url) {

    static MediaView of(MediaSnapshot media) {
        ObjectNode sizes = JsonNodeFactory.instance.objectNode();
        media.sizes()
                .forEach((name, size) -> sizes.putObject(name)
                        .put("file", size.file())
                        .put("width", size.width())
                        .put("height", size.height())
                        .put("mimeType", size.mimeType())
                        .put("fileSize", size.fileSize())
                        .put("url", media.url(name)));
        return new MediaView(
                media.post().id().value(),
                media.post().slug(),
                media.post().title(),
                media.post().excerpt(),
                media.post().content(),
                media.post().status(),
                media.post().author().map(UserId::value).orElse(0L),
                media.post().parent().map(PostId::value).orElse(0L),
                media.post().guid(),
                media.mimeType(),
                media.storageKey(),
                media.attachedFile(),
                media.originalImage().orElse(null),
                media.fileSize(),
                media.width().isPresent() ? media.width().getAsInt() : null,
                media.height().isPresent() ? media.height().getAsInt() : null,
                sizes,
                media.imageMeta().orElse(null),
                media.wordPressMetadata(),
                media.altText(),
                media.processing().name().toLowerCase(java.util.Locale.ROOT),
                media.url());
    }
}
