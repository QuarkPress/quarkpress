package org.quarkpress.media;

import java.util.Objects;

/** A file a processor wrote: its type, its dimensions and its size in bytes. */
public record Encoded(String mimeType, int width, int height, long bytes) {

    public Encoded {
        Objects.requireNonNull(mimeType, "mimeType");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions are positive: " + width + "x" + height);
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("a file size is not negative: " + bytes);
        }
    }
}
