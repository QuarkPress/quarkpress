package org.quarkword.media;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decodes, cuts, scales and encodes images, as WordPress's image editors do. An image processor module provides one
 * bean; without one, uploaded images are stored without generated sizes.
 */
public interface ImageProcessor {

    /**
     * Whether this processor accepts images of {@code mimeType} as a source: it decodes them, or only measures them;
     * it does not necessarily write that type (see {@link #writes}).
     */
    boolean supports(String mimeType);

    /**
     * Whether this processor writes copies as {@code mimeType}. By default, every type it {@link #supports supports}.
     */
    default boolean writes(String mimeType) {
        return supports(mimeType);
    }

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
