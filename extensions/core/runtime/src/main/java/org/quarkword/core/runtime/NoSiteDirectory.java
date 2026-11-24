package org.quarkword.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.quarkword.core.SiteDirectory;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteInfo;

/** Knows no site; replaced by the persistence module's directory. */
@DefaultBean
@ApplicationScoped
public class NoSiteDirectory implements SiteDirectory {

    @Override
    public Optional<SiteInfo> find(SiteId site) {
        return Optional.empty();
    }
}
