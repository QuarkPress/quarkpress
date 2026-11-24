package org.quarkword.media.deployment;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;

/**
 * Announces an image processor module ({@code imageio}, {@code vips}) by the name {@code
 * quarkword.media.image-processor} chooses it with; the media module fails the build when that setting names a
 * module that is not installed.
 */
public final class ImageProcessorBuildItem extends MultiBuildItem {

    private final String name;

    public ImageProcessorBuildItem(String name) {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("an image processor module has a name");
        }
        this.name = name;
    }

    /** The module's name, as {@code quarkword.media.image-processor} names it. */
    public String name() {
        return name;
    }
}
