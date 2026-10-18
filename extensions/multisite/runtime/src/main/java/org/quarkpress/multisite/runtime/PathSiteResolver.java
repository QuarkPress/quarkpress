package org.quarkpress.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;

/** Sites under a path of the network domain; the main site answers paths that are not a site's. */
@ApplicationScoped
@Priority(100)
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "true")
public class PathSiteResolver implements SiteResolver {

    @Inject
    SiteRouting routing;

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return routing.match(ResolverStrategy.PATH, request);
    }
}
