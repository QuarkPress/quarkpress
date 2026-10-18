package org.quarkpress.multisite.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.quarkpress.core.SiteId;
import org.quarkpress.multisite.SiteDomain;

/** A further domain of a site. Network data: no site filter applies. */
@Entity
@Table(name = "qp_site_domain")
public class SiteDomainEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "site_id", nullable = false)
    public long siteId;

    @Column(name = "domain", nullable = false, length = 200)
    public String domain;

    @Column(name = "redirect_to_primary", nullable = false)
    public boolean redirectToPrimary;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static SiteDomainEntity create(SiteId site, String domain, boolean redirectToPrimary, Instant now) {
        SiteDomainEntity entity = new SiteDomainEntity();
        entity.siteId = site.value();
        entity.domain = domain;
        entity.redirectToPrimary = redirectToPrimary;
        entity.createdAt = now;
        return entity;
    }

    public SiteDomain toDomain() {
        return new SiteDomain(domain, SiteId.of(siteId), redirectToPrimary, createdAt);
    }
}
