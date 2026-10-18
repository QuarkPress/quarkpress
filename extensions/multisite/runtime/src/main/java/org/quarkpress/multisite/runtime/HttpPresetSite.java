package org.quarkpress.multisite.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.common.vertx.ContextLocals;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

/**
 * Hands the site binding the decision {@link SiteRouteFilter} stored for the current HTTP request: through
 * {@code CurrentVertxRequest} under Quarkus REST, otherwise from the request's Vert.x duplicated context (code that runs
 * before Quarkus REST, such as security, {@code @Route} handlers and other filters). Outside a duplicated context
 * (jobs, scopes for another site on their own virtual threads) there is none.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "true")
public class HttpPresetSite implements PresetSite {

    @Inject
    CurrentVertxRequest request;

    @Override
    public Optional<Binding> binding() {
        return routed().map(RoutedSite::binding);
    }

    @Override
    public Optional<URI> redirect() {
        return routed().flatMap(RoutedSite::redirect);
    }

    private Optional<RoutedSite> routed() {
        if (Arc.container().requestContext().isActive()) {
            RoutingContext context = request.getCurrent();
            if (context != null && context.get(SiteRouteFilter.KEY) instanceof RoutedSite site) {
                return Optional.of(site);
            }
        }
        if (!VertxContext.isOnDuplicatedContext()) {
            return Optional.empty();
        }
        Optional<Object> stored = ContextLocals.get(SiteRouteFilter.KEY);
        return stored.filter(RoutedSite.class::isInstance).map(RoutedSite.class::cast);
    }
}
