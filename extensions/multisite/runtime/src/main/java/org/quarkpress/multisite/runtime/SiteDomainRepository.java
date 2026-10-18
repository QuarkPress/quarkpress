package org.quarkpress.multisite.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SiteDomainRepository implements PanacheRepositoryBase<SiteDomainEntity, Long> {

    /** The alias {@code domain}; domains are stored normalized. */
    public Optional<SiteDomainEntity> findByDomain(String domain) {
        return find("domain = ?1", domain).firstResultOptional();
    }

    public List<SiteDomainEntity> forSite(long site) {
        return list("siteId = ?1 order by domain", site);
    }
}
