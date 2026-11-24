package org.quarkword.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;

/** Domain aliases, and sites on domains outside the network domain (WordPress's domain mapping). */
@ApplicationScoped
@Priority(300)
@IfBuildProperty(name = "quarkword.multisite.enabled", stringValue = "true")
public class DomainSiteResolver implements SiteResolver {

    @Inject
    SiteRouting routing;

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return routing.match(ResolverStrategy.DOMAIN, request);
    }
}
