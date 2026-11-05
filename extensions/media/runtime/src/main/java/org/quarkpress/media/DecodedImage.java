package org.quarkpress.media;

import java.nio.file.Path;

/**
 * An image a processor decoded, held in memory until it is closed. Every operation returns a new image and leaves
 * its receiver unchanged; the caller closes each image it was given.
 */
public interface DecodedImage extends AutoCloseable {

    int width();

    int height();

    /**
     * The image turned upright as the EXIF orientation value says (2 to 8: mirrored and/or rotated); {@code this} for
     * 0, 1 and any other value.
     */
    DecodedImage oriented(int exifOrientation);

    /**
     * Cuts the rectangle at ({@code x}, {@code y}) of {@code w} by {@code h} pixels out of this image, then scales it
     * to {@code targetWidth} by {@code targetHeight}.
     */
    DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight);

    /**
     * Writes the image to {@code target} as {@code mimeType}; {@code quality} (1 to 100) applies to lossy formats.
     *
     * @throws ImageProcessingException when the image cannot be written in that type
     */
    Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException;

    /** Releases the image's memory. */
    @Override
    void close();
}
