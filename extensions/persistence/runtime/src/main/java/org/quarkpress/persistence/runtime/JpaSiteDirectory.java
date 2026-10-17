package org.quarkpress.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;

/** Looks sites up in qp_site. */
@ApplicationScoped
public class JpaSiteDirectory implements SiteDirectory {

    @Inject
    SiteRepository sites;

    @Override
    @Transactional
    public Optional<SiteInfo> find(SiteId site) {
        return sites.findByIdOptional(site.value()).map(SiteEntity::toInfo);
    }
}
