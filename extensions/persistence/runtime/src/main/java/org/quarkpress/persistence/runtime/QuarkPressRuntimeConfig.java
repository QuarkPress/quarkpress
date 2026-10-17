package org.quarkpress.persistence.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** QuarkPress settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface QuarkPressRuntimeConfig {

    /** The id of the main site: the site every request belongs to when the multisite module is not installed. */
    @WithDefault("1")
    long mainSiteId();
}
