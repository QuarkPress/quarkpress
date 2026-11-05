package org.quarkpress.media;

import java.util.Objects;

/**
 * A registered image size: its name, its box (0 = unbounded in that dimension) and how it is cut. A negative width or
 * height is taken as its absolute value, as WordPress does when a size is registered.
 */
public record ImageSizeDefinition(String name, int width, int height, Crop crop) {

    public ImageSizeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(crop, "crop");
        width = nonNegative(width);
        height = nonNegative(height);
    }

    private static int nonNegative(int value) {
        return value == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(value);
    }
}
