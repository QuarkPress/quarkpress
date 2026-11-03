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

    /**
     * Whether front-end requests of a site's root ({@code ?p=}, {@code ?page_id=}, {@code ?attachment_id=},
     * {@code ?post_type=...&p=}, {@code ?preview=}) get the minimal page (the site's name, the post's title, date and
     * content) until themes exist; when off, no front-end request is answered by this module.
     */
    @WithDefault("true")
    boolean fallbackFrontEnd();

    /** Which other origins may call the REST API from a browser. */
    interface Cors {

        /**
         * The origins answered with {@code Access-Control-Allow-Origin}; none by default, so no response carries that
         * header.
         */
        Optional<List<String>> origins();
    }
}
