package org.quarkpress.blocks;

import java.util.List;

/**
 * A CDI bean that registers block pattern categories. Providers are read by {@link #order()}, then by class name; a
 * category registered again under the same name replaces the earlier one where it stood. The core categories come
 * from a provider of order 0.
 */
public interface BlockPatternCategoryProvider {

    List<BlockPatternCategory> categories();

    /** Lower orders register first. */
    default int order() {
        return 1000;
    }
}
