package org.quarkpress.core;

import java.net.URI;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The site the current request works for. Inject it anywhere; it follows the request's binding, including bindings
 * opened with the persistence module's site and network scopes.
 */
public interface SiteContext {

    boolean isResolved();

    /** The current site; throws {@link SiteNotResolvedException} when there is none. */
    SiteId siteId();

    /** The current site's record; throws {@link SiteNotResolvedException} when there is none or it does not exist. */
    SiteInfo site();

    /** The site's features; with no current site every feature is off. */
    SiteFeatures features();

    /** The site's options; throws {@link SiteNotResolvedException} when there is no current site. */
    SiteOptions options();

    /**
     * The site's time zone from its {@code timezone_string} or {@code gmt_offset} option, UTC when neither gives one;
     * throws {@link SiteNotResolvedException} when there is no current site.
     */
    ZoneId zone();

    /** Where the request should be redirected when it arrived on a redirecting alias domain. */
    Optional<URI> redirect();
}
