package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.restwp.PhpObjectNode;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * An attachment's {@code media_details} as WordPress answers them: the stored metadata in its stored order, each size
 * with its type last under {@code mime_type} followed by its URL, an image's main file added last among the sizes as
 * {@code full}, and an empty object where the metadata has no sizes. An attachment without metadata (still being
 * made, failed, or without a stored file) has empty details.
 *
 * <p>Loaded only by the media beans: it names the media module's types.
 */
final class MediaDetails {

    private static final String SIZES = "sizes";

    private MediaDetails() {}

    /**
     * The details of {@code media}, whose main file's URL (as the response shows it, its scheme included) is
     * {@code mainUrl}: {@code {}} while its metadata is being made or could not be made, for an attachment without a
     * stored file, and when its metadata is empty; otherwise its metadata with each size entry's {@code mime-type}
     * moved last as {@code mime_type}, followed by {@code source_url} (the main URL with every occurrence of the main
     * file's name replaced by the size's file); sizes that are stored but empty become {@code {}} in place and absent
     * ones are added last as {@code {}}; for an image with sizes, {@code full} (its file name, the stored width and
     * height, the attachment's type and the main URL) is added after the stored sizes.
     */
    static ObjectNode of(MediaSnapshot media, String mainUrl) {
        if (media.processing() == Processing.PENDING
                || media.processing() == Processing.FAILED
                || media.attachedFile().isEmpty()) {
            return new PhpObjectNode();
        }
        ObjectNode metadata = media.wordPressMetadata();
        if (metadata.isEmpty()) {
            return new PhpObjectNode();
        }
        String mainName = baseName(media.attachedFile());
        ObjectNode details = RestJson.NODES.objectNode();
        boolean hasSizes = false;
        for (Map.Entry<String, JsonNode> entry : metadata.properties()) {
            if (!entry.getKey().equals(SIZES)) {
                details.set(entry.getKey(), entry.getValue());
                continue;
            }
            hasSizes = true;
            JsonNode stored = entry.getValue();
            if (!stored.isObject() || stored.isEmpty()) {
                details.set(SIZES, new PhpObjectNode());
                continue;
            }
            ObjectNode sizes = details.putObject(SIZES);
            for (Map.Entry<String, JsonNode> size : stored.properties()) {
                sizes.set(size.getKey(), size(size.getValue(), mainUrl, mainName));
            }
            if (media.mimeType().startsWith("image/")
                    && media.width().isPresent()
                    && media.height().isPresent()) {
                sizes.putObject(MediaSnapshot.FULL)
                        .put("file", mainName)
                        .put("width", media.width().getAsInt())
                        .put("height", media.height().getAsInt())
                        .put("mime_type", media.mimeType())
                        .put("source_url", mainUrl);
            }
        }
        if (!hasSizes) {
            details.set(SIZES, new PhpObjectNode());
        }
        return details;
    }

    /** A size entry: its keys without {@code mime-type}, that type as {@code mime_type}, then its URL. */
    private static JsonNode size(JsonNode stored, String mainUrl, String mainName) {
        if (!stored.isObject()) {
            return stored;
        }
        ObjectNode size = RestJson.NODES.objectNode();
        stored.properties().forEach(entry -> {
            if (!entry.getKey().equals("mime-type")) {
                size.set(entry.getKey(), entry.getValue());
            }
        });
        if (stored.has("mime-type")) {
            size.set("mime_type", stored.get("mime-type"));
        }
        JsonNode file = stored.get("file");
        if (!mainUrl.isEmpty() && !mainName.isEmpty() && file != null && file.isTextual()) {
            size.put("source_url", mainUrl.replace(mainName, file.textValue()));
        }
        return size;
    }

    /** The part of {@code path} after its last slash. */
    static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
