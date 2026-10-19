package org.quarkpress.security.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** Security settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.security")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface SecurityConfig {

    /**
     * OIDC subjects ({@code sub}) that are made network administrators at every start (a profile is created for a
     * subject that has none). Without the multisite module they become administrators of the main site instead,
     * because a single site has no network administrators.
     */
    Optional<List<String>> initialSuperAdmins();

    /** Refuse {@code unfiltered_html} (and {@code edit_css}) to everyone, network administrators included. */
    @WithDefault("false")
    boolean disallowUnfilteredHtml();
}
