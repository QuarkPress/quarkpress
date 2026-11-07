package org.quarkpress.media;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.posts.PostSnapshot;

/**
 * An attachment as stored: its post and the file side of it.
 *
 * @param post the attachment post
 * @param mimeType the attachment's type (WordPress's {@code post_mime_type})
 * @param storageKey the main file's full storage key ({@code sites/<id>/} included for a site other than the main one)
 * @param attachedFile the main file's path relative to the site's uploads folder (WordPress's
 *     {@code _wp_attached_file}), such as {@code 2026/11/photo.jpg}
 * @param originalImage the file name of the untouched upload, when the main file is a scaled, rotated or converted
 *     copy of it
 * @param fileSize the main file's size in bytes
 * @param width the main file's width, when known
 * @param height the main file's height, when known
 * @param sizes the generated sizes by name, in generation order
 * @param imageMeta the embedded data of an image ({@code image_meta}), when it was read
 * @param extra every other metadata key, in order (audio and video keys, imported keys)
 * @param altText the alternative text
 * @param processing how far the generated files are made
 * @param url the main file's public URL
 */
public record MediaSnapshot(
        PostSnapshot post,
        String mimeType,
        String storageKey,
        String attachedFile,
        Optional<String> originalImage,
        long fileSize,
        OptionalInt width,
        OptionalInt height,
        Map<String, MediaSize> sizes,
        Optional<ObjectNode> imageMeta,
        ObjectNode extra,
        String altText,
        Processing processing,
        String url) {

    /** The name of the main file among the sizes. */
    public static final String FULL = "full";

    public MediaSnapshot {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(attachedFile, "attachedFile");
        Objects.requireNonNull(originalImage, "originalImage");
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(imageMeta, "imageMeta");
        Objects.requireNonNull(extra, "extra");
        Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(processing, "processing");
        Objects.requireNonNull(url, "url");
        sizes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(sizes, "sizes")));
        imageMeta = imageMeta.map(ObjectNode::deepCopy);
        extra = extra.deepCopy();
    }

    /** A copy of the embedded data. */
    @Override
    public Optional<ObjectNode> imageMeta() {
        return imageMeta.map(ObjectNode::deepCopy);
    }

    /** A copy of the other metadata keys. */
    @Override
    public ObjectNode extra() {
        return extra.deepCopy();
    }

    /**
     * The public URL of the size {@code sizeName}: the main file's for {@link #FULL}, else the size file's, in the
     * main file's folder.
     *
     * @throws IllegalArgumentException when the attachment has no size of that name
     */
    public String url(String sizeName) {
        if (FULL.equals(sizeName)) {
            return url;
        }
        MediaSize size = sizes.get(sizeName);
        if (size == null) {
            throw new IllegalArgumentException("the attachment has no size named " + sizeName);
        }
        return url.substring(0, url.lastIndexOf('/') + 1) + size.file();
    }

    /**
     * The attachment's metadata as WordPress stores it ({@code _wp_attachment_metadata}): empty while the upload is
     * pending; for an image of known dimensions {@code width}, {@code height}, {@code file}, {@code filesize},
     * {@code sizes}, {@code image_meta}, then {@code original_image} when there is one, then the other keys; for any
     * other file (a video of known dimensions included) the other keys, then {@code filesize} unless they hold one.
     */
    public ObjectNode wordPressMetadata() {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        if (processing == Processing.PENDING) {
            return metadata;
        }
        if (mimeType.startsWith("image/") && width.isPresent() && height.isPresent()) {
            metadata.put("width", width.getAsInt());
            metadata.put("height", height.getAsInt());
            metadata.put("file", attachedFile);
            metadata.put("filesize", fileSize);
            ObjectNode sizeEntries = metadata.putObject("sizes");
            sizes.forEach((name, size) -> sizeEntries
                    .putObject(name)
                    .put("file", size.file())
                    .put("width", size.width())
                    .put("height", size.height())
                    .put("mime-type", size.mimeType())
                    .put("filesize", size.fileSize()));
            imageMeta.ifPresent(meta -> metadata.set("image_meta", meta.deepCopy()));
            originalImage.ifPresent(name -> metadata.put("original_image", name));
            extra.properties()
                    .forEach(entry ->
                            metadata.set(entry.getKey(), entry.getValue().deepCopy()));
            return metadata;
        }
        extra.properties()
                .forEach(entry -> metadata.set(entry.getKey(), entry.getValue().deepCopy()));
        if (!metadata.has("filesize")) {
            metadata.put("filesize", fileSize);
        }
        return metadata;
    }
}
