package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.quarkpress.core.SiteId;

@ApplicationScoped
public class SiteRepository implements PanacheRepositoryBase<SiteEntity, Long> {

    /** The site's row, locked for update (PESSIMISTIC_WRITE) until the transaction ends. */
    public Optional<SiteEntity> lockForUpdate(SiteId site) {
        return findByIdOptional(site.value(), LockModeType.PESSIMISTIC_WRITE);
    }
}
