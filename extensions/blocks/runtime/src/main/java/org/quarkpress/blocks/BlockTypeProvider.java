package org.quarkpress.blocks;

import java.util.List;

/**
 * A CDI bean that registers block types. Providers are read by {@link #order()}, then by class name; their types are
 * registered in list order. A name registered twice stops the application at start-up.
 */
public interface BlockTypeProvider {

    List<BlockType> blockTypes();

    /** Lower orders register first. */
    default int order() {
        return 1000;
    }
}
