package org.quarkword.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;

/** Sites on subdomains of the network domain. */
@ApplicationScoped
@Priority(200)
@IfBuildProperty(name = "quarkword.multisite.enabled", stringValue = "true")
public class SubdomainSiteResolver implements SiteResolver {

    @Inject
    SiteRouting routing;

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return routing.match(ResolverStrategy.SUBDOMAIN, request);
    }
}
