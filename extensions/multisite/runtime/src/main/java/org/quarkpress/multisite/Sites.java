package org.quarkpress.multisite;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.SiteFlagsChanged;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;

/**
 * The network's sites: WordPress's network administration of sites. Writes require the network capabilities
 * {@code create_sites}, {@code manage_sites} or {@code delete_sites} (else {@link CapabilityDeniedException}) and run
 * in a network transaction of their own, one after the other: called from a request bound to a site, they commit
 * independently of the caller. A change to the site the calling request is bound to, made inside that request's
 * transaction, is refused with {@link IllegalStateException}; make it outside the transaction. Events are fired inside
 * the network transaction (an observer that throws rolls the change back) and again to asynchronous observers after it
 * commits.
 *
 * <p>The main site ({@code quarkpress.main-site-id}) is the network's address. Its flags cannot change and it cannot
 * be deleted; its domain and path can change, which moves the network. Reads need no request context.
 */
public interface Sites {

    /**
     * Creates a site with WordPress's default options, then fires {@code SiteCreated} (preceded by
     * {@link SiteFlagsChanged} when the public value is not 1, as WordPress fires its status transitions on insert).
     * Refuses an address another site has, a domain that is an alias (with or without {@code www.}), and a reserved
     * first path segment for a subdirectory site of the network.
     * Also refuses a domain that normalizes to nothing or to {@code 0}, a path no request can reach (dot segments, a
     * {@code 0} segment, an interior {@code //}) and a path under the non-application root; the message shows the
     * normalized path, as stored, cut to 64 characters.
     */
    SiteInfo create(NewSite site);

    Optional<SiteInfo> find(SiteId site);

    /** The site at a domain and path, both normalized first; no prefix matching. */
    Optional<SiteInfo> findByAddress(String domain, String path);

    /** Sites in id order; {@code offset} from 0, {@code limit} 1 to 1000. */
    List<SiteInfo> list(int offset, int limit);

    /**
     * Changes a site's domain, path or name and fires {@code SiteUpdated}; a new name is also written to the site's
     * {@code blogname} option. Returns the site unchanged, firing nothing, when nothing changes.
     */
    SiteInfo update(SiteId site, SiteChanges changes);

    /** Sets one flag; fires {@code SiteUpdated} then {@link SiteFlagsChanged} when it changes. */
    SiteInfo setFlag(SiteId site, SiteFlag flag, boolean value);

    /** Sets WordPress's {@code public} value; fires {@code SiteUpdated} then {@link SiteFlagsChanged} when it changes. */
    SiteInfo setPublic(SiteId site, int publicFlag);

    /** Deletes a site with all its rows, then fires {@code SiteDeleted}. */
    void delete(SiteId site);
}
