package org.quarkpress.multisite;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;

/**
 * Further domains of sites (aliases). A domain is normalized like a site domain, may be the alias of one site only,
 * and may not be any site's own domain. Writes require the network capability {@code manage_sites} and run in a
 * network transaction of their own.
 */
public interface SiteDomains {

    /** Adds an alias; with {@code redirectToPrimary}, requests on it report the site's own address as a redirect. */
    SiteDomain add(SiteId site, String domain, boolean redirectToPrimary);

    /** Removes an alias; returns whether it existed (false for text that does not normalize to a domain). */
    boolean remove(String domain);

    /** The alias, or empty when none has this domain or the text does not normalize to a domain. */
    Optional<SiteDomain> find(String domain);

    /** The aliases of a site, by domain. */
    List<SiteDomain> forSite(SiteId site);
}
