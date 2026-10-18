package org.quarkpress.multisite;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteId;

/**
 * A further domain of a site. A request on it resolves to the site; when {@code redirectToPrimary} is set, the site
 * context reports the site's own address as the place to redirect to.
 */
public record SiteDomain(String domain, SiteId site, boolean redirectToPrimary, Instant createdAt) {

    public SiteDomain {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
