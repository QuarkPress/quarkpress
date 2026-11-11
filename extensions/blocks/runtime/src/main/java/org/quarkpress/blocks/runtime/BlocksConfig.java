package org.quarkpress.blocks.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Settings of the block registries, read when the application starts. */
@ConfigMapping(prefix = "quarkpress.blocks")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface BlocksConfig {

    /**
     * How many blocks the nested patterns of one pattern may insert when its content is resolved. Nested references
     * fan out (a pattern holding two references to a pattern that holds two references, and so on), so the work is
     * bounded: once this many blocks were inserted, the later references are left as they are and one warning is
     * logged. WordPress has no such bound.
     */
    @WithDefault("10000")
    int maxResolvedBlocks();
}
