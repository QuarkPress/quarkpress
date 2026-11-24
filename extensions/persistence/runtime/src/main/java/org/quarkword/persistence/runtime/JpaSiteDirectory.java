package org.quarkword.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.quarkword.core.SiteDirectory;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteInfo;

/** Looks sites up in qw_site. */
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
