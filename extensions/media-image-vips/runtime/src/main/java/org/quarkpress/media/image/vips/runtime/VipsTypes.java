package org.quarkpress.media.image.vips.runtime;

import java.util.Optional;
import org.sitenetsoft.quarkus.libvips.Image;

/** The image type of a file libvips loaded, told by the loader that read it. */
public final class VipsTypes {

    private VipsTypes() {}

    /** The type of {@code image}, from its loader; empty for a loader whose type no processor handles. */
    public static Optional<String> mimeOf(Image image) {
        String loader = image.loader();
        return mimeOf(loader, loader.startsWith("heifload") ? image.field("heif-compression") : Optional.empty());
    }

    /**
     * The type a file read by {@code loader} has: a loader nickname such as {@code jpegload}, {@code
     * jpegload_buffer} or {@code jpegload_source}. A HEIF container is AVIF when its {@code heifCompression} is {@code
     * av1}, HEIC when it is {@code hevc}, and HEIF otherwise. Empty for any other loader.
     */
    public static Optional<String> mimeOf(String loader, Optional<String> heifCompression) {
        int end = loader.indexOf('_');
        String base = end < 0 ? loader : loader.substring(0, end);
        return switch (base) {
            case "jpegload" -> Optional.of("image/jpeg");
            case "pngload" -> Optional.of("image/png");
            case "gifload" -> Optional.of("image/gif");
            case "webpload" -> Optional.of("image/webp");
            case "tiffload" -> Optional.of("image/tiff");
            case "heifload" ->
                Optional.of(
                        switch (heifCompression.orElse("")) {
                            case "av1" -> "image/avif";
                            case "hevc" -> "image/heic";
                            default -> "image/heif";
                        });
            default -> Optional.empty();
        };
    }
}
