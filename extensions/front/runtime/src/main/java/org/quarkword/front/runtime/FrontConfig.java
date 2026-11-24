package org.quarkword.front.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/** Front-end settings read when the application starts. */
@ConfigMapping(prefix = "quarkword.front")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface FrontConfig {

    /**
     * The site address (WordPress's {@code WP_HOME}): overrides the {@code home} option of a single site, its
     * trailing slash removed; ignored when multisite is enabled.
     */
    Optional<String> home();

    /**
     * The application address (WordPress's {@code WP_SITEURL}): overrides the {@code siteurl} option of a single
     * site, its trailing slash removed; ignored when multisite is enabled.
     */
    Optional<String> siteurl();

    /**
     * Whether admin and login URLs are always https (WordPress's {@code FORCE_SSL_ADMIN}); they are anyway when
     * {@code siteurl} is stored with https.
     */
    @WithDefault("false")
    boolean forceSslAdmin();

    /** The {@code Cache-Control} header of anonymous front-end answers; none by default. */
    Optional<String> cacheControl();
}
