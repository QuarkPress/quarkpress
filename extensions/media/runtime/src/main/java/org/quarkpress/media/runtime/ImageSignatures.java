package org.quarkpress.media.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * The real image type of an uploaded file, as WordPress recognises images: by content, for the image types it can
 * rename a file to, and by its own reading of the first twelve bytes for WebP and the HEIF family.
 */
public final class ImageSignatures {

    /** The image types Tika's answer is taken for. */
    private static final Set<String> RECOGNISED = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/bmp",
            "image/tiff",
            "image/webp",
            "image/avif",
            "image/heic",
            "image/heif",
            "image/heic-sequence",
            "image/heif-sequence");

    private ImageSignatures() {}

    /**
     * The real image type of {@code file}: Tika's content-only answer when it is one of JPEG, PNG, GIF, BMP, TIFF,
     * WebP, AVIF or the four HEIF-family types; otherwise the first twelve bytes decide ({@code RIFF} with
     * {@code WEBP} at offset 8 is WebP; an {@code ftyp} box with brand {@code avif} or {@code avis} is AVIF, {@code
     * heic} HEIC, {@code heif} HEIF). Tika knows only some HEIF and AVIF brands and calls other ISO media files
     * {@code video/quicktime}, which is why the brands are read here.
     *
     * @param file the uploaded file
     * @return the image type, or empty when the content is not one of those images (an icon, a Photoshop file, not
     *     an image at all)
     * @throws IOException when the file cannot be read
     */
    public static Optional<String> realType(Path file) throws IOException {
        return realType(TikaTypes.prefix(file));
    }

    /** The real image type of a file that starts with {@code prefix}. */
    static Optional<String> realType(byte[] prefix) throws IOException {
        String tika = TikaTypes.detect(prefix);
        if (RECOGNISED.contains(tika)) {
            return Optional.of(tika);
        }
        if (prefix.length < 12) {
            return Optional.empty();
        }
        if (ascii(prefix, 0, "RIFF") && ascii(prefix, 8, "WEBP")) {
            return Optional.of("image/webp");
        }
        if (ascii(prefix, 4, "ftyp")) {
            if (ascii(prefix, 8, "avif") || ascii(prefix, 8, "avis")) {
                return Optional.of("image/avif");
            }
            if (ascii(prefix, 8, "heic")) {
                return Optional.of("image/heic");
            }
            if (ascii(prefix, 8, "heif")) {
                return Optional.of("image/heif");
            }
        }
        return Optional.empty();
    }

    private static boolean ascii(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
