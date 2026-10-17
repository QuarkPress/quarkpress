package org.quarkpress.core.deployment.gate;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;

@ApplicationScoped
public class SiteOne implements SiteResolver {

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return Optional.of(SiteId.of(1));
    }
}
