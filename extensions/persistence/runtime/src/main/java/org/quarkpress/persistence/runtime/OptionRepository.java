package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;

/**
 * Site options. Every query names the site, on top of the site filter. Names must already be normalized: leading or
 * trailing whitespace is refused with {@link IllegalArgumentException}.
 */
@ApplicationScoped
public class OptionRepository implements PanacheRepositoryBase<OptionEntity, Long> {

    public Optional<OptionEntity> findOption(SiteId site, String name) {
        return find("siteId = ?1 and name = ?2", TenantIds.param(site), StoredOptionNames.require(name))
                .firstResultOptional();
    }

    /**
     * The option {@code name}, locked for update (PESSIMISTIC_WRITE) until the transaction ends, with its state read
     * from the database even when the row was already loaded in this transaction.
     */
    public Optional<OptionEntity> findOptionForUpdate(SiteId site, String name) {
        Optional<OptionEntity> option = find(
                        "siteId = ?1 and name = ?2", TenantIds.param(site), StoredOptionNames.require(name))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional();
        option.ifPresent(getEntityManager()::refresh);
        return option;
    }

    public List<OptionEntity> autoloaded(SiteId site) {
        return list("siteId = ?1 and autoload = true", TenantIds.param(site));
    }
}
