package org.quarkpress.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.smallrye.common.vertx.ContextLocals;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteRequest;

/**
 * Decides the site of every HTTP request before any route runs: the resolvers run once, on a worker thread (the event
 * loop never waits for the database), and the decision is stored on the request, where the request's site binding
 * adopts it. Requests whose normalized path lies under the non-application root are not resolved. A request whose
 * host matches no site is served without a site; a resolver or database failure fails the request (500), it never
 * binds "no site" or another site.
 *
 * <p>Ordering: filters with a higher priority run first. {@link #PRIORITY} is 250: after host validation (400) and
 * CORS (300), before authentication (200), form authentication (150) and authorization (100), so security code, such
 * as an identity augmentor that reads roles on the current site, already sees the request's site. The decision is
 * also kept as a local of the request's Vert.x duplicated context, for code that runs before Quarkus REST.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "true")
public class SiteRouteFilter {

    /** Where the decision is stored on the routing context and in the duplicated context. */
    public static final String KEY = "org.quarkpress.multisite.routed-site";

    /** Filters with a higher priority run first; this one runs before authentication. */
    public static final int PRIORITY = 250;

    @Inject
    HttpResolution resolution;

    void register(@Observes Filters filters) {
        filters.register(this::route, PRIORITY);
    }

    void route(RoutingContext context) {
        if (NonApplicationPath.covers(context.normalizedPath())) {
            context.next();
            return;
        }
        if (context.get(KEY) != null) {
            // a re-routed request keeps the decision made for it
            context.next();
            return;
        }
        HttpServerRequest http = context.request();
        if (http.headers().getAll("Host").size() > 1) {
            context.fail(400);
            return;
        }
        if (!SiteRequests.hostsAgree(http)) {
            context.fail(400);
            return;
        }
        SiteRequest request = SiteRequests.of(http);
        context.vertx()
                .<RoutedSite>executeBlocking(() -> resolution.route(request), false)
                .onComplete(result -> {
                    if (result.failed()) {
                        context.fail(result.cause());
                        return;
                    }
                    RoutedSite routed = result.result();
                    context.put(KEY, routed);
                    if (VertxContext.isOnDuplicatedContext()) {
                        ContextLocals.put(KEY, routed);
                    }
                    context.next();
                });
    }
}
