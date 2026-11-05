package org.quarkpress.media;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decodes, cuts, scales and encodes images, as WordPress's image editors do. An image processor module provides one
 * bean; without one, uploaded images are stored without generated sizes.
 */
public interface ImageProcessor {

    /** Whether this processor both reads and writes images of {@code mimeType}. */
    boolean supports(String mimeType);

    /**
     * The detected image type and the stored dimensions of {@code file}, never rotated by its EXIF orientation;
     * empty when the file is not an image this processor can read.
     */
    Optional<ImageInfo> read(Path file);

    /**
     * Decodes {@code file}, an image of {@code mimeType}.
     *
     * @throws ImageProcessingException when it cannot be decoded
     */
    DecodedImage decode(Path file, String mimeType) throws ImageProcessingException;
}
