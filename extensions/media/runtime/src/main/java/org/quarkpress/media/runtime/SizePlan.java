package org.quarkpress.media.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.runtime.Dimensions.Size;

/**
 * Which copies of an image are made, in which order, and under which file names. Copies are not de-duplicated: two
 * sizes that come out with the same dimensions name the same file.
 */
public final class SizePlan {

    /** Made first, when present, so that a usable size exists early if processing stops midway. */
    private static final List<String> FIRST = List.of("medium", "large", "thumbnail", "medium_large");

    private SizePlan() {}

    /** {@code medium}, {@code large}, {@code thumbnail} and {@code medium_large} when present, then the others. */
    public static List<String> generationOrder(Collection<String> names) {
        List<String> order = new ArrayList<>(names.size());
        for (String first : FIRST) {
            if (names.contains(first)) {
                order.add(first);
            }
        }
        for (String name : names) {
            if (!FIRST.contains(name) && !order.contains(name)) {
                order.add(name);
            }
        }
        return List.copyOf(order);
    }

    /**
     * {@code {base}-{w}x{h}.{ext}}: the source name without its last extension, the copy's actual dimensions, and the
     * source's extension in lower case, or the output type's own extension when the copy is of another type.
     */
    public static String subSizeName(String sourceName, int width, int height, String sourceType, String outputType) {
        return withExtension(base(sourceName) + "-" + width + "x" + height, sourceName, sourceType, outputType);
    }

    /** The main-file copy of an image larger than the big-image threshold: {@code {base}-scaled.{ext}}. */
    public static String scaledName(String sourceName, String sourceType, String outputType) {
        return withExtension(base(sourceName) + "-scaled", sourceName, sourceType, outputType);
    }

    /** The main-file copy of an image turned upright from its EXIF orientation: {@code {base}-rotated.{ext}}. */
    public static String rotatedName(String sourceName, String sourceType, String outputType) {
        return withExtension(base(sourceName) + "-rotated", sourceName, sourceType, outputType);
    }

    /** The main-file copy of an image written in another type: {@code {base}.{ext of the output type}}. */
    public static String convertedName(String sourceName, String outputType) {
        String extension = extensionOf(outputType).orElseGet(() -> extension(sourceName));
        return join(base(sourceName), extension);
    }

    /**
     * The size of the main-file copy for the big-image threshold: present only when the image is larger than {@code
     * threshold} in width or height (strictly), the threshold is not 0, and fitting the image into a
     * thresholdxthreshold box changes it by more than one pixel in some dimension.
     */
    public static Optional<Size> scaled(int w0, int h0, int threshold) {
        if (threshold <= 0 || (w0 <= threshold && h0 <= threshold)) {
            return Optional.empty();
        }
        return Dimensions.resize(w0, h0, threshold, threshold, Crop.none()).map(r -> new Size(r.width(), r.height()));
    }

    /**
     * The type copies of a {@code sourceType} image are written in: the type {@code outputFormats} maps it to when
     * {@code processor} writes that type, otherwise the source type.
     */
    public static String outputType(String sourceType, Map<String, String> outputFormats, ImageProcessor processor) {
        String mapped = outputFormats.get(sourceType);
        if (mapped == null || mapped.equals(sourceType) || !processor.supports(mapped)) {
            return sourceType;
        }
        return mapped;
    }

    private static String withExtension(String stem, String sourceName, String sourceType, String outputType) {
        String extension = sourceType.equals(outputType)
                ? extension(sourceName)
                : extensionOf(outputType).orElseGet(() -> extension(sourceName));
        return join(stem, extension);
    }

    private static String join(String stem, String extension) {
        return extension.isEmpty() ? stem : stem + "." + extension;
    }

    /** The default extension of an image type a copy can be written in. */
    private static Optional<String> extensionOf(String mimeType) {
        return Optional.ofNullable(
                switch (mimeType) {
                    case "image/jpeg" -> "jpg";
                    case "image/png" -> "png";
                    case "image/gif" -> "gif";
                    case "image/webp" -> "webp";
                    case "image/avif" -> "avif";
                    case "image/bmp" -> "bmp";
                    case "image/heic" -> "heic";
                    default -> null;
                });
    }

    /** The index of the dot that starts the last extension of the name's last path segment, or -1. */
    private static int lastDot(String name) {
        int dot = name.lastIndexOf('.');
        return dot > name.lastIndexOf('/') ? dot : -1;
    }

    private static String base(String name) {
        int dot = lastDot(name);
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String extension(String name) {
        int dot = lastDot(name);
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
