package org.quarkpress.media;

import java.util.Objects;

/** What a processor reads from an image file: its type and its stored dimensions, before any EXIF rotation. */
public record ImageInfo(String mimeType, int width, int height) {

    public ImageInfo {
        Objects.requireNonNull(mimeType, "mimeType");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions are positive: " + width + "x" + height);
        }
    }
}
