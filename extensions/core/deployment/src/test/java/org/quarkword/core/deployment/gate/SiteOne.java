package org.quarkword.core.deployment.gate;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;

@ApplicationScoped
public class SiteOne implements SiteResolver {

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return Optional.of(SiteId.of(1));
    }
}
