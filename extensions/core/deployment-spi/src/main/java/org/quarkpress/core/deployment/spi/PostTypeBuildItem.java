package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;
import org.quarkpress.core.PostTypeDefinition;

/**
 * Registers a post type for every site (WordPress's {@code register_post_type}). Build the definition with
 * {@code PostTypeDefinition.builder}, which applies WordPress's defaults. A key that another type already has, the
 * built-in ones included, fails the build.
 */
public final class PostTypeBuildItem extends MultiBuildItem {

    private final PostTypeDefinition definition;

    public PostTypeBuildItem(PostTypeDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    public PostTypeDefinition definition() {
        return definition;
    }
}
