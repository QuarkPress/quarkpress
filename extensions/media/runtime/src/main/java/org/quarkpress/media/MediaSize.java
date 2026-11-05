package org.quarkpress.media;

import java.util.Objects;

/**
 * A generated size of an image, as WordPress lists it in the attachment's metadata.
 *
 * @param file the size's file name, in the folder of the main file
 * @param width its width in pixels
 * @param height its height in pixels
 * @param mimeType its type
 * @param fileSize its size in bytes
 */
public record MediaSize(String file, int width, int height, String mimeType, long fileSize) {

    public MediaSize {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
