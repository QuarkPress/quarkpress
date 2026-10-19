package org.quarkpress.security.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Settings that apply in dev mode only. */
@ConfigMapping(prefix = "quarkpress.dev")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DevConfig {

    /** In dev mode, give the dev realm's users their memberships on the main site at startup. */
    @WithDefault("true")
    boolean seedMemberships();
}
