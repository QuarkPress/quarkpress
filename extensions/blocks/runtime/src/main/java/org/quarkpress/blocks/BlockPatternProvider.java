package org.quarkpress.blocks;

import java.util.List;

/**
 * A CDI bean that registers block patterns. Providers are read by {@link #order()}, then by class name; a pattern
 * registered again under the same name replaces the earlier one where it stood.
 */
public interface BlockPatternProvider {

    List<BlockPattern> patterns();

    /** Lower orders register first. */
    default int order() {
        return 1000;
    }
}
