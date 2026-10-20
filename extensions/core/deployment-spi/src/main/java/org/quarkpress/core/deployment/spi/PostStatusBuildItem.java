package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;
import org.quarkpress.core.PostStatusDefinition;

/**
 * Registers a post status for every site (WordPress's {@code register_post_status}). A name that another status
 * already has, the built-in ones included, fails the build.
 */
public final class PostStatusBuildItem extends MultiBuildItem {

    private final PostStatusDefinition definition;

    public PostStatusBuildItem(PostStatusDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    public PostStatusDefinition definition() {
        return definition;
    }
}
