package org.quarkpress.media.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.quarkpress.media.Crop;

/**
 * WordPress's size arithmetic: fitting an image into a box, cutting it to a box, and the rules that decide that no
 * copy is made. Computed in doubles as PHP does, with PHP's rounding, and truncated to integers at the end.
 */
public final class Dimensions {

    /** A width and a height in pixels. */
    public record Size(int width, int height) {}

    /**
     * What to cut from the source ({@code srcX}, {@code srcY}, {@code srcWidth}, {@code srcHeight}) and the size it is
     * scaled to ({@code width}, {@code height}). In fit mode the region is the whole source.
     */
    public record Region(int srcX, int srcY, int srcWidth, int srcHeight, int width, int height) {}

    /** From here on a double has no fraction, and beyond it a cast to long saturates. */
    private static final double LONG_LIMIT = 0x1p62;

    private Dimensions() {}

    /**
     * The size of a {@code w0}x{@code h0} image fitted into a {@code maxWidth}x{@code maxHeight} box (0 = no
     * limit), keeping its aspect ratio and never enlarging it. A result one pixel short of a limit it was scaled to is
     * bumped to the limit, so constraining a size that was constrained already gives the same size.
     */
    public static Size constrain(int w0, int h0, int maxWidth, int maxHeight) {
        if (maxWidth == 0 && maxHeight == 0) {
            return new Size(w0, h0);
        }
        double widthRatio = 1.0;
        double heightRatio = 1.0;
        boolean didWidth = false;
        boolean didHeight = false;
        if (maxWidth > 0 && w0 > maxWidth) {
            widthRatio = maxWidth / (double) w0;
            didWidth = true;
        }
        if (maxHeight > 0 && h0 > maxHeight) {
            heightRatio = maxHeight / (double) h0;
            didHeight = true;
        }
        double smaller = Math.min(widthRatio, heightRatio);
        double larger = Math.max(widthRatio, heightRatio);
        double ratio = round(w0 * larger) <= maxWidth && round(h0 * larger) <= maxHeight ? larger : smaller;
        long width = Math.max(1, round(w0 * ratio));
        long height = Math.max(1, round(h0 * ratio));
        if (didWidth && width == maxWidth - 1L) {
            width = maxWidth;
        }
        if (didHeight && height == maxHeight - 1L) {
            height = maxHeight;
        }
        return new Size((int) width, (int) height);
    }

    /**
     * The copy of a {@code w0}x{@code h0} image for a {@code width}x{@code height} box (0 = unbounded) with {@code
     * crop}, or empty when no copy is made: the image or the box has no size, the image is smaller than the box (in the
     * one bounded dimension, or in both), or the copy would be within one pixel of the image in both dimensions.
     */
    public static Optional<Region> resize(int w0, int h0, int width, int height, Crop crop) {
        if (w0 <= 0 || h0 <= 0 || (width <= 0 && height <= 0)) {
            return Optional.empty();
        }
        if ((height == 0 && w0 < width) || (width == 0 && h0 < height) || (w0 < width && h0 < height)) {
            return Optional.empty();
        }
        double newWidth;
        double newHeight;
        double srcX = 0;
        double srcY = 0;
        double srcWidth;
        double srcHeight;
        if (crop instanceof Crop.None) {
            Size fitted = constrain(w0, h0, width, height);
            newWidth = fitted.width();
            newHeight = fitted.height();
            srcWidth = w0;
            srcHeight = h0;
        } else {
            double aspect = w0 / (double) h0;
            newWidth = Math.min(width, w0);
            newHeight = Math.min(height, h0);
            if (newWidth == 0) {
                newWidth = round(newHeight * aspect);
            }
            if (newHeight == 0) {
                newHeight = round(newWidth / aspect);
            }
            double scale = Math.max(newWidth / w0, newHeight / h0);
            srcWidth = round(newWidth / scale);
            srcHeight = round(newHeight / scale);
            String x = crop instanceof Crop.At at ? at.x() : "center";
            String y = crop instanceof Crop.At at ? at.y() : "center";
            srcX = switch (x) {
                case "left" -> 0;
                case "right" -> w0 - srcWidth;
                default -> Math.floor((w0 - srcWidth) / 2);
            };
            srcY = switch (y) {
                case "top" -> 0;
                case "bottom" -> h0 - srcHeight;
                default -> Math.floor((h0 - srcHeight) / 2);
            };
        }
        if (Math.abs(newWidth - w0) <= 1 && Math.abs(newHeight - h0) <= 1) {
            return Optional.empty();
        }
        return Optional.of(
                new Region((int) srcX, (int) srcY, (int) srcWidth, (int) srcHeight, (int) newWidth, (int) newHeight));
    }

    /**
     * PHP's {@code round} to an integer: half away from zero on the value's shortest decimal form (the digits that
     * read back as the same double). Not-finite values and values beyond {@code long} saturate as a cast does.
     */
    public static long round(double value) {
        if (!Double.isFinite(value) || Math.abs(value) >= LONG_LIMIT) {
            return (long) value;
        }
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * PHP's {@code round} to {@code places} decimal places (negative places round to tens, hundreds and so on): half
     * away from zero on the value's shortest decimal form, so {@code round(1.005, 2)} is 1.01. Not-finite values are
     * returned as they are.
     */
    public static double round(double value, int places) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
