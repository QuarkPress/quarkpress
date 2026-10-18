package org.quarkpress.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;

/** Domain aliases, and sites on domains outside the network domain (WordPress's domain mapping). */
@ApplicationScoped
@Priority(300)
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "true")
public class DomainSiteResolver implements SiteResolver {

    @Inject
    SiteRouting routing;

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return routing.match(ResolverStrategy.DOMAIN, request);
    }
}
