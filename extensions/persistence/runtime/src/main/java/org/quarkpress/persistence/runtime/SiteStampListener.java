package org.quarkpress.persistence.runtime;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Field;
import org.hibernate.Hibernate;

/**
 * Stamps the current site on insert and refuses writes for another site. Updates and deletes are compared with the
 * site recorded on this Java object when it was loaded or stored (not with its site field), so a row loaded around
 * the filter (for example by native SQL) cannot be written from another site, and an object that was never loaded or
 * stored is refused. Nothing is written while no site is bound.
 *
 * <p>Inserts and updates are also refused when a mapped association points at a loaded row of another site; removals
 * are not, so rows that point across sites can be cleaned up. A reference that was never loaded (a proxy from
 * {@code getReference}) is not loaded here; the composite foreign key refuses it in the database.
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
        } else {
            long current = Long.parseLong(tenant);
            if (entity.siteIdValue() == null) {
                entity.stampSite(current);
            } else if (entity.siteIdValue() != current) {
                throw new IllegalStateException(
                        "row for site " + entity.siteIdValue() + " written under site " + current);
            }
        }
        checkAssociations(entity);
    }

    @PostLoad
    @PostPersist
    void remember(SiteScopedEntity entity) {
        entity.rememberStoredSite();
    }

    @PreUpdate
    void guardUpdate(SiteScopedEntity entity) {
        guard(entity);
        checkAssociations(entity);
    }

    /** Removals are not checked for associations: deleting a row that points at another site's row must stay possible. */
    @PreRemove
    void guardRemove(SiteScopedEntity entity) {
        guard(entity);
    }

    private static void guard(SiteScopedEntity entity) {
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

    private static void checkAssociations(SiteScopedEntity entity) {
        Long own = entity.siteIdValue();
        for (Field field : SiteAssociations.of(entity.getClass())) {
            Object target = SiteAssociations.read(field, entity);
            if (target == null || !Hibernate.isInitialized(target)) {
                continue;
            }
            if (Hibernate.unproxy(target) instanceof SiteScopedEntity referenced
                    && referenced.siteIdValue() != null
                    && !referenced.siteIdValue().equals(own)) {
                throw new IllegalStateException("a row of site " + own + " refers to a row of site "
                        + referenced.siteIdValue() + " through "
                        + field.getDeclaringClass().getSimpleName() + "."
                        + field.getName());
            }
        }
    }
}
