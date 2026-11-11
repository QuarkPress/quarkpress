package org.quarkpress.blocks;

/**
 * Renders a dynamic block on the server. A CDI bean implementing it makes the block type it names dynamic; the block
 * type must be registered by a {@link BlockTypeProvider}, and one type has at most one renderer, or the application
 * does not start.
 */
public interface BlockRenderer {

    /** The name of the block type this renderer renders, {@code <namespace>/<name>}. */
    String blockName();

    /** The block's HTML; {@code null} counts as the empty string. */
    String render(BlockRenderContext context);
}
