package org.quarkpress.blocks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * What a {@link BlockRenderer} renders: the block type's name, the block's attributes (with their defaults) and the
 * post the block is rendered for, if any. The attributes are copied in and out, so a renderer cannot change the
 * caller's node.
 */
public record BlockRenderContext(String name, ObjectNode attributes, OptionalLong postId) {

    public BlockRenderContext {
        Objects.requireNonNull(name, "name");
        attributes = Objects.requireNonNull(attributes, "attributes").deepCopy();
        Objects.requireNonNull(postId, "postId");
    }

    @Override
    public ObjectNode attributes() {
        return attributes.deepCopy();
    }
}
