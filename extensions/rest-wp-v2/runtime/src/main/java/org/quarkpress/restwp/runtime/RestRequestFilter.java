package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.multisite.runtime.SiteRouteFilter;

/**
 * Sends REST requests to the REST entry resource. A request is a REST request when its query has a non-empty
 * {@code rest_route}, when its path is a REST path of its site (the configured base right after the site's path, see
 * {@link RestPaths}), or when it is a form POST to the site's path or {@code index.php} there (the resource then reads
 * {@code rest_route} from the form). Every other request, including one under the non-application root or one whose
 * path holds the base below another prefix, is passed on untouched.
 *
 * <p>Runs after the multisite module's site resolution (whose decision gives the site's path) and before
 * authentication. A REST request is re-routed to {@link RestPaths#entryPath()} with what it asked for kept on the
 * routing context; the re-routed pass goes straight on.
 */
@ApplicationScoped
public class RestRequestFilter {

    /** Where the REST request is kept on the routing context; the entry resource serves only requests that carry it. */
    public static final String ENTRY = "org.quarkpress.restwp.entry";

    /** Filters with a higher priority run first: after site resolution (250), before authentication (200). */
    public static final int PRIORITY = 240;

    /**
     * A request sent to the REST server: its method, raw path and parsed query, the route its path addresses (null when
     * the path is not a REST path, still percent-encoded) and whether {@code rest_route} may come from its form fields.
     */
    public record Entry(String method, String path, ObjectNode query, String pathRoute, boolean form) {}

    @Inject
    RestWpConfig config;

    @Inject
    SiteDirectory directory;

    private volatile String base;

    void register(@Observes Filters filters) {
        base = RestPaths.normalizeBase(config.path());
        filters.register(this::filter, PRIORITY);
    }

    void filter(RoutingContext context) {
        if (context.get(ENTRY) != null) {
            // the re-routed pass of a REST request
            context.next();
            return;
        }
        if (RestPaths.underNonApplicationRoot(context.normalizedPath())) {
            context.next();
            return;
        }
        HttpServerRequest request = context.request();
        String path = request.path() == null ? "/" : request.path();
        ObjectNode query = PhpQuery.parse(request.query());
        JsonNode restRoute = query.get("rest_route");
        // a form POST can only be one to a site's path or to index.php there
        boolean formPost = request.method() == HttpMethod.POST
                && (path.endsWith("/") || endsWithIndex(path))
                && IncomingRequest.isForm(request.getHeader("Content-Type"));
        if (!formPost) {
            if (restRoute != null) {
                // the query's rest_route wins over the path; an empty one makes the request no REST request
                if (phpEmpty(restRoute)) {
                    context.next();
                } else {
                    reroute(context, new Entry(request.method().name(), path, query, null, false));
                }
                return;
            }
            if (!path.contains(base)) {
                context.next();
                return;
            }
        }
        Object routed = context.get(SiteRouteFilter.KEY);
        Optional<SiteId> site = routed == null ? Optional.empty() : RoutedSites.site(routed);
        if (site.isEmpty()) {
            decide(context, path, query, "/", formPost);
            return;
        }
        SiteId id = site.get();
        context.vertx()
                .<String>executeBlocking(
                        () -> directory.find(id).map(SiteInfo::path).orElse("/"), false)
                .onComplete(result -> {
                    if (result.failed()) {
                        context.fail(result.cause());
                    } else {
                        decide(context, path, query, result.result(), formPost);
                    }
                });
    }

    /**
     * Decides a request that may be a REST request through its path or its form, once its site's path is known: a
     * form POST to the site's path (or {@code index.php} there) lets the resource read {@code rest_route} from the
     * form and compare it with the query's.
     */
    private void decide(RoutingContext context, String path, ObjectNode query, String sitePath, boolean formPost) {
        boolean form = formPost && (samePath(path, sitePath) || samePath(path, sitePath + "index.php"));
        JsonNode restRoute = query.get("rest_route");
        Optional<String> route = RestPaths.route(path, sitePath, base);
        boolean rest = restRoute == null ? route.isPresent() || form : !phpEmpty(restRoute) || form;
        if (rest) {
            reroute(context, new Entry(context.request().method().name(), path, query, route.orElse(null), form));
        } else {
            context.next();
        }
    }

    private static boolean endsWithIndex(String path) {
        String index = "/index.php";
        return path.length() >= index.length()
                && RestPaths.startsWithIgnoringAsciiCase(path.substring(path.length() - index.length()), index);
    }

    private static boolean samePath(String path, String sitePath) {
        return path.length() == sitePath.length() && RestPaths.startsWithIgnoringAsciiCase(path, sitePath);
    }

    /**
     * Re-routes as a POST, whatever the method, so that the entry resource reads every request's body (WordPress reads
     * a JSON body for any method); the entry keeps the real method, and a HEAD response still goes out without a body.
     */
    private static void reroute(RoutingContext context, Entry entry) {
        context.put(ENTRY, entry);
        context.reroute(HttpMethod.POST, RestPaths.entryPath() + "/route");
    }

    /** PHP's {@code empty()} of a query value: an empty string or {@code "0"}. */
    static boolean phpEmpty(JsonNode value) {
        return value.isTextual()
                && (value.textValue().isEmpty() || value.textValue().equals("0"));
    }
}
