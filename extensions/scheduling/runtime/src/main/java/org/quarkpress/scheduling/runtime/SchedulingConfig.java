package org.quarkpress.scheduling.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

/** Scheduling settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.scheduling")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface SchedulingConfig {

    /**
     * Whether this module's jobs run on this node. {@code false} stops every job of this module here (the Quarkus
     * scheduler itself stays as configured).
     */
    @WithDefault("true")
    boolean enabled();

    /** How often due posts are looked for. WordPress has no interval: its latency depends on traffic. */
    @WithDefault("15s")
    Duration interval();

    /**
     * How many due posts are published per site per poll (the rest wait for the next poll), and how many rows the
     * housekeeping reads per page. A value below 1 counts as 1.
     */
    @WithDefault("100")
    int batchSize();

    /** How often the trash and old auto-drafts are purged, counted from application start. */
    @WithDefault("24h")
    Duration housekeepingInterval();
}
