package org.quarkpress.it.tenancy;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/** Stamps the site on insert and refuses writes for another site. */
@RegisterForReflection
public class SiteStampListener {

    @PrePersist
    void stamp(SiteScopedEntity entity) {
        String tenant = CurrentSiteIdSupplier.current();
        if ("-1".equals(tenant)) {
            if (entity.siteId == null) {
                throw new IllegalStateException("a network-scope insert must name its site");
            }
            return;
        }
        long current = Long.parseLong(tenant);
        if (entity.siteId == null) {
            entity.siteId = current;
        } else if (entity.siteId != current) {
            throw new IllegalStateException("row for site " + entity.siteId + " written under site " + current);
        }
    }

    @PreUpdate
    @PreRemove
    void guard(SiteScopedEntity entity) {
        String tenant = CurrentSiteIdSupplier.current();
        if (!"-1".equals(tenant) && entity.siteId != Long.parseLong(tenant)) {
            throw new IllegalStateException("row for site " + entity.siteId + " changed under site " + tenant);
        }
    }
}
