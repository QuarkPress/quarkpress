package org.quarkpress.persistence.runtime;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/**
 * Stamps the current site on insert and refuses writes for another site. Updates and deletes are compared with the
 * site recorded on this Java object when it was loaded or stored (not with its site field), so a row loaded around
 * the filter (for example by native SQL) cannot be written from another site, and an object that was never loaded or
 * stored is refused. Nothing is written while no site is bound.
 */
public class SiteStampListener {

    @PrePersist
    void stamp(SiteScopedEntity entity) {
        String tenant = CurrentSiteIdSupplier.current();
        if (TenantIds.UNRESOLVED.equals(tenant)) {
            throw new IllegalStateException("no site is bound: a site-scoped row needs a site");
        }
        if (TenantIds.NETWORK.equals(tenant)) {
            if (entity.siteIdValue() == null) {
                throw new IllegalStateException("a network-scope insert must name its site");
            }
            return;
        }
        long current = Long.parseLong(tenant);
        if (entity.siteIdValue() == null) {
            entity.stampSite(current);
        } else if (entity.siteIdValue() != current) {
            throw new IllegalStateException("row for site " + entity.siteIdValue() + " written under site " + current);
        }
    }

    @PostLoad
    @PostPersist
    void remember(SiteScopedEntity entity) {
        entity.rememberStoredSite();
    }

    @PreUpdate
    @PreRemove
    void guard(SiteScopedEntity entity) {
        String tenant = CurrentSiteIdSupplier.current();
        Long stored = entity.storedSiteId();
        if (stored == null || !stored.equals(entity.siteIdValue())) {
            throw new IllegalStateException("the site of a stored row cannot change");
        }
        if (TenantIds.UNRESOLVED.equals(tenant)) {
            throw new IllegalStateException("no site is bound: a site-scoped row needs a site");
        }
        if (!TenantIds.NETWORK.equals(tenant) && stored != Long.parseLong(tenant)) {
            throw new IllegalStateException("row of site " + stored + " changed under site " + tenant);
        }
    }
}
