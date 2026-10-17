package org.quarkpress.persistence.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.quarkpress.core.SiteId;

/**
 * Rows that belong to one site. An always-enabled filter limits queries and loads by id to the current site (the
 * network value -1 sees every site, 0 sees none), and {@link SiteStampListener} stamps inserts and refuses writes for
 * another site. Each site-scoped entity, and each collection of site-scoped elements, adds
 * {@code @Filter(name = SiteScopedEntity.FILTER)}; the build checks it.
 *
 * <p>The site of a stored row cannot change: the field is private, {@link #assignSite} only works on new rows, and
 * the listener compares writes with the site the row had when it was loaded or stored, not with the field.
 */
@MappedSuperclass
@FilterDef(
        name = SiteScopedEntity.FILTER,
        autoEnabled = true,
        applyToLoadByKey = true,
        defaultCondition = "(:siteId = -1 or site_id = :siteId)",
        parameters = @ParamDef(name = "siteId", type = Long.class, resolver = CurrentSiteIdSupplier.class))
@EntityListeners(SiteStampListener.class)
public abstract class SiteScopedEntity {

    public static final String FILTER = "qp_site";

    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    /** The site the row had when it was loaded or stored; null while the row is new. */
    @Transient
    private Long storedSiteId;

    /** The site of this row; fails while a new row has no site yet (it is stamped on insert). */
    public SiteId site() {
        if (siteId == null) {
            throw new IllegalStateException("this row has no site yet: a new row gets its site when it is stored");
        }
        return SiteId.of(siteId);
    }

    /** Names the site of a new row; required for inserts in the network scope. Refused once the row is stored. */
    protected void assignSite(SiteId site) {
        if (storedSiteId != null) {
            throw new IllegalStateException("the site of a stored row cannot change");
        }
        this.siteId = site.value();
    }

    Long siteIdValue() {
        return siteId;
    }

    void stampSite(long site) {
        this.siteId = site;
    }

    Long storedSiteId() {
        return storedSiteId;
    }

    void rememberStoredSite() {
        this.storedSiteId = siteId;
    }
}
