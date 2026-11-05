package org.quarkpress.media.deployment;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;

/**
 * Announces a media storage backend module ({@code fs}, {@code s3}); its {@code MediaStorage} bean carries
 * {@code @Identifier} with the same name. The media module fails the build without any backend, and asks for
 * {@code quarkpress.media.storage} when there are several.
 */
public final class MediaStorageBuildItem extends MultiBuildItem {

    private final String name;

    public MediaStorageBuildItem(String name) {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("a media storage backend has a name");
        }
        this.name = name;
    }

    /** The backend's name, as {@code quarkpress.media.storage} names it. */
    public String name() {
        return name;
    }
}
