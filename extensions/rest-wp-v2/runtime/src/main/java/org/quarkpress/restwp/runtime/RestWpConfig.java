package org.quarkpress.restwp.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** REST API settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.rest-wp-v2")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface RestWpConfig {

    /** The base path of the REST API under each site's path, as WordPress's {@code rest_get_url_prefix} is. */
    @WithDefault("/wp-json")
    String path();

    /** Cross-origin requests. */
    Cors cors();

    /** Which other origins may call the REST API from a browser. */
    interface Cors {

        /**
         * The origins answered with {@code Access-Control-Allow-Origin}; none by default, so no response carries that
         * header.
         */
        Optional<List<String>> origins();
    }
}
