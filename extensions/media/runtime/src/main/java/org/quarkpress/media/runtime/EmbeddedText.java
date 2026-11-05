package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.quarkpress.posts.runtime.Slugs;

/**
 * The title, caption and alternative text an upload takes from its embedded metadata when the request gives none.
 *
 * @param title the embedded title, trimmed; empty when there is none or when its slug is a number (a title such as
 *     {@code 2024} or {@code 20250612} is a camera counter or a date, not a title)
 * @param caption the embedded caption, as read
 * @param alt the embedded alternative text, as read
 */
public record EmbeddedText(String title, String caption, String alt) {

    /** The three texts of an {@code image_meta} object as {@link ImageMetaReader#read} returns it. */
    public static EmbeddedText of(ObjectNode imageMeta) {
        String title = ImageMetaReader.trim(text(imageMeta, "title"));
        if (!title.isEmpty() && ExifNumbers.isNumeric(Slugs.sanitizeTitle(title))) {
            title = "";
        }
        return new EmbeddedText(title, text(imageMeta, "caption"), text(imageMeta, "alt"));
    }

    private static String text(ObjectNode meta, String key) {
        JsonNode value = meta.get(key);
        return value != null && value.isTextual() ? value.textValue() : "";
    }
}
