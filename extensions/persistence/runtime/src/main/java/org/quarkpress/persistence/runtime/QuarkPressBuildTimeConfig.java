package org.quarkpress.persistence.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** QuarkPress settings fixed when the application is built. */
@ConfigMapping(prefix = "quarkpress")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface QuarkPressBuildTimeConfig {

    /** Persistence settings. */
    Persistence persistence();

    /** Multisite settings. */
    Multisite multisite();

    interface Persistence {

        /**
         * The folder of vendor-specific migrations under {@code db/quarkpress}: {@code postgresql} or {@code mysql}.
         * Defaults to the db-kind of the {@code quarkpress} datasource; MariaDB sets {@code mysql}.
         */
        String migrationVendor();
    }

    interface Multisite {

        /**
         * Whether the multisite module resolves sites. Without it every request belongs to the main site; the
         * multisite module turns this on.
         */
        @WithDefault("false")
        boolean enabled();
    }
}
