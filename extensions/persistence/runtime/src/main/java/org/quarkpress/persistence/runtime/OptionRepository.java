package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
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

    public List<OptionEntity> autoloaded(SiteId site) {
        return list("siteId = ?1 and autoload = true", TenantIds.param(site));
    }
}
