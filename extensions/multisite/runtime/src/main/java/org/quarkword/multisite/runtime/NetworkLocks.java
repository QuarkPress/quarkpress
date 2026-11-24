package org.quarkword.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.runtime.NetworkLockRepository;
import org.quarkword.persistence.runtime.QuarkWordRuntimeConfig;
import org.quarkword.persistence.runtime.SiteEntity;
import org.quarkword.persistence.runtime.SiteRepository;

/**
 * How network writes keep out of each other's way: each takes the {@code qw_network_lock} row first, which no foreign
 * key refers to, so network writes run one after the other without waiting for transactions that inserted a site's
 * rows (those hold shared locks on the site's {@code qw_site} row, which network writes therefore never lock). Site
 * rows are read without a lock after the network lock and changed under their version.
 */
@ApplicationScoped
public class NetworkLocks {

    @Inject
    NetworkLockRepository lock;

    @Inject
    SiteRepository sites;

    @Inject
    QuarkWordRuntimeConfig config;

    /**
     * Locks the network lock row until the transaction ends; call it as the network write's first statement. From then
     * on the transaction waits for each lock at most {@value NetworkLockRepository#LOCK_TIMEOUT_SECONDS} seconds.
     */
    public void network() {
        lock.lock();
    }

    /** The main site's row, read without a lock; take {@link #network()} first. */
    public SiteEntity main() {
        long id = config.mainSiteId();
        return sites.findByIdOptional(id)
                .orElseThrow(() -> new IllegalStateException("the main site " + id + " does not exist"));
    }

    /** A site's row, read without a lock and changed under its version; take {@link #network()} first. */
    public SiteEntity site(SiteId site) {
        return sites.findByIdOptional(site.value())
                .orElseThrow(() -> new IllegalArgumentException("site " + site.value() + " does not exist"));
    }
}
