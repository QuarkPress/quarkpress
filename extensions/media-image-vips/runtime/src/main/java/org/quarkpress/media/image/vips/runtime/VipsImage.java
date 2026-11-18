package org.quarkpress.media.image.vips.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageProcessingException;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Keep;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.SaveOptions;

/**
 * An image libvips decoded. Turning, cutting and scaling make new libvips images; the pixels are computed when the
 * image is written. Copies keep the colour profile and no other metadata, except PNG copies, which keep none.
 */
final class VipsImage implements DecodedImage {

    private static final Logger LOG = Logger.getLogger(VipsImage.class);

    private static final Set<Keep> PROFILE_ONLY = Set.of(Keep.ICC);

    private static final String FORMAT_PREFIX = "VIPS_FORMAT_";

    private final Image image;
    private final VipsFormats formats;

    VipsImage(Image image, VipsFormats formats) {
        this.image = image;
        this.formats = formats;
    }

    @Override
    public int width() {
        return image.width();
    }

    @Override
    public int height() {
        return image.height();
    }

    @Override
    public DecodedImage oriented(int exifOrientation) {
        return switch (exifOrientation) {
            case 2 -> turned(null, "horizontal");
            case 3 -> turned("d180", null);
            case 4 -> turned(null, "vertical");
            case 5 -> turned("d90", "horizontal");
            case 6 -> turned("d90", null);
            case 7 -> turned("d270", "horizontal");
            case 8 -> turned("d270", null);
            default -> this;
        };
    }

    /** Rotates clockwise by {@code angle} (when given), then flips in {@code direction} (when given). */
    private VipsImage turned(String angle, String direction) {
        try {
            if (angle == null) {
                return new VipsImage(image.call("flip", Map.of("direction", direction)), formats);
            }
            Image rotated = image.call("rot", Map.of("angle", angle));
            if (direction == null) {
                return new VipsImage(rotated, formats);
            }
            try (rotated) {
                return new VipsImage(rotated.call("flip", Map.of("direction", direction)), formats);
            }
        } catch (LibvipsException failure) {
            throw failed("turn", failure);
        }
    }

    /**
     * Cuts the rectangle out and scales it to exactly the target size. An image with alpha is scaled with its colours
     * weighted by their opacity, so that transparent pixels lend no colour to the pixels next to them.
     */
    @Override
    public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
        try {
            Image cut = image.crop(x, y, w, h);
            if (w == targetWidth && h == targetHeight) {
                return new VipsImage(cut, formats);
            }
            Image scaled;
            try (cut) {
                scaled = scaled(cut, targetWidth, targetHeight, w, h);
            }
            if (scaled.width() != targetWidth || scaled.height() != targetHeight) {
                int sw = scaled.width();
                int sh = scaled.height();
                scaled.close();
                throw new IllegalStateException("libvips scaled " + w + "x" + h + " to " + sw + "x" + sh
                        + " instead of " + targetWidth + "x" + targetHeight);
            }
            return new VipsImage(scaled, formats);
        } catch (LibvipsException failure) {
            throw failed("cut", failure);
        }
    }

    private static Image scaled(Image cut, int targetWidth, int targetHeight, int w, int h) {
        Map<String, Object> scale = Map.of("scale", targetWidth / (double) w, "vscale", targetHeight / (double) h);
        if (!cut.hasAlpha()) {
            return cut.call("resize", scale);
        }
        String format = bandFormat(cut);
        try (Image premultiplied = cut.call("premultiply", Map.of());
                Image resized = premultiplied.call("resize", scale);
                Image unpremultiplied = resized.call("unpremultiply", Map.of())) {
            return unpremultiplied.call("cast", Map.of("format", format));
        }
    }

    /**
     * The nick of the image's band format, such as {@code uchar}: libvips gives the field as {@code ((VipsBandFormat)
     * VIPS_FORMAT_UCHAR)}.
     */
    static String bandFormat(Image image) {
        String text = image.field("format").orElse("");
        int start = text.indexOf(FORMAT_PREFIX);
        if (start < 0) {
            return "uchar";
        }
        int end = start + FORMAT_PREFIX.length();
        while (end < text.length() && Character.isLetter(text.charAt(end))) {
            end++;
        }
        return text.substring(start + FORMAT_PREFIX.length(), end).toLowerCase(Locale.ROOT);
    }

    @Override
    public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
        return encode(target, mimeType, quality, false);
    }

    /**
     * Writes the image as {@code mimeType}, with its colour profile and no other metadata: JPEG baseline at {@code
     * quality} (alpha dropped, as the other processor drops it), PNG at compression level 9 without any metadata, not
     * even the profile (as WordPress's editors write PNG copies), WebP at {@code quality} or lossless, AVIF at {@code
     * quality}, GIF as one frame. The quality is held within 1 to 100.
     */
    @Override
    public Encoded encode(Path target, String mimeType, int quality, boolean lossless) throws ImageProcessingException {
        if (!formats.writes(mimeType)) {
            throw new ImageProcessingException("libvips does not write " + mimeType + ".");
        }
        int q = Math.clamp(quality, 1, 100);
        SaveOptions options = switch (mimeType) {
            case "image/jpeg" -> new SaveOptions.Jpeg(q, false, PROFILE_ONLY);
            case "image/png" -> new SaveOptions.Png(9, false, Set.of());
            case "image/webp" -> new SaveOptions.Webp(q, lossless, 4, PROFILE_ONLY);
            case "image/avif" -> new SaveOptions.Avif(q, false, 2, PROFILE_ONLY);
            case "image/gif" -> SaveOptions.gif();
            default -> throw new ImageProcessingException("libvips does not write " + mimeType + ".");
        };
        byte[] bytes;
        try {
            if (mimeType.equals("image/jpeg") && image.hasAlpha()) {
                try (Image opaque = image.call("extract_band", Map.of("band", 0, "n", image.bands() - 1))) {
                    bytes = opaque.save(options);
                }
            } else {
                bytes = image.save(options);
            }
        } catch (LibvipsException failure) {
            LOG.debugf(
                    "libvips could not encode an image as %s: %s: %s",
                    mimeType, failure.getMessage(), failure.detail());
            throw new ImageProcessingException("libvips could not encode the image as " + mimeType + ".", failure);
        }
        try {
            Files.write(target, bytes);
        } catch (IOException failure) {
            LOG.debugf(failure, "an encoded image could not be written to its file");
            throw new ImageProcessingException("The encoded image could not be written to its file.");
        }
        return new Encoded(mimeType, image.width(), image.height(), bytes.length);
    }

    @Override
    public void close() {
        image.close();
    }

    private static IllegalStateException failed(String what, LibvipsException failure) {
        LOG.debugf("libvips could not %s an image: %s: %s", what, failure.getMessage(), failure.detail());
        return new IllegalStateException("libvips could not " + what + " the image.", failure);
    }
}
