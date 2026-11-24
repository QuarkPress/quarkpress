package org.quarkword.security.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.persistence.runtime.TenantIds;

/** Site memberships. Every query names the site, on top of the site filter. */
@ApplicationScoped
public class SiteMembershipRepository implements PanacheRepositoryBase<SiteMembershipEntity, Long> {

    public Optional<SiteMembershipEntity> findMembership(SiteId site, UserId user) {
        return find("siteId = ?1 and userId = ?2", TenantIds.param(site), user.value())
                .firstResultOptional();
    }
}
