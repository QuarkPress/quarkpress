package org.quarkpress.restwp.runtime;

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
 * Sends front-end page requests to the preview page resource, as {@link RestRequestFilter} sends REST requests to the
 * REST entry resource. A request is a front-end page request when the fallback front end is on
 * ({@code quarkpress.rest-wp-v2.fallback-front-end}), it is a {@code GET} or {@code HEAD} of its site's root (the
 * site's path, or {@code index.php} there) and its query names a post ({@code p}, {@code page_id},
 * {@code attachment_id}, {@code post_type} with {@code p}) or asks for a preview ({@code preview}), and has no
 * {@code rest_route} at all. Every other request is passed on untouched, so a request with {@code rest_route} is the
 * REST filter's whatever else its query holds.
 *
 * <p>This filter only decides and re-routes: it reads no cookie and calls no service. The re-routed request goes
 * through authentication, the request scope and the site binding as any request does, and the resource renders the
 * page inside that request. Runs after the multisite module's site resolution (whose decision gives the site's path)
 * and before authentication, at the REST filter's priority: the two decide disjoint requests.
 */
@ApplicationScoped
public class FrontEndFilter {

    /** Where the page request is kept on the routing context; the resource serves only requests that carry it. */
    public static final String ENTRY = "org.quarkpress.restwp.front-end";

    /** The query parameters that make a site-root request a page request. */
    static final String[] PAGE_PARAMS = {"p", "page_id", "attachment_id", "preview"};

    /** A front-end page request: its method, raw path and parsed query, and the site's path. */
    public record Entry(String method, String path, ObjectNode query, String sitePath) {}

    @Inject
    RestWpConfig config;

    @Inject
    SiteDirectory directory;

    void register(@Observes Filters filters) {
        filters.register(this::filter, RestRequestFilter.PRIORITY);
    }

    void filter(RoutingContext context) {
        if (context.get(ENTRY) != null || context.get(RestRequestFilter.ENTRY) != null) {
            // the re-routed pass of a page or REST request
            context.next();
            return;
        }
        if (!config.fallbackFrontEnd() || RestPaths.underNonApplicationRoot(context.normalizedPath())) {
            context.next();
            return;
        }
        HttpServerRequest request = context.request();
        HttpMethod method = request.method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            context.next();
            return;
        }
        String path = request.path() == null ? "/" : request.path();
        if (!path.endsWith("/") && !endsWithIndex(path)) {
            context.next();
            return;
        }
        ObjectNode query = PhpQuery.parse(request.query());
        if (query.has("rest_route") || !pageParams(query)) {
            context.next();
            return;
        }
        Object routed = context.get(SiteRouteFilter.KEY);
        Optional<SiteId> site = routed == null ? Optional.empty() : RoutedSites.site(routed);
        if (site.isEmpty()) {
            decide(context, method, path, query, "/");
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
                        decide(context, method, path, query, result.result());
                    }
                });
    }

    /** Re-routes a page request of the site's root, once its site's path is known. */
    private static void decide(
            RoutingContext context, HttpMethod method, String path, ObjectNode query, String sitePath) {
        if (samePath(path, sitePath) || samePath(path, sitePath + "index.php")) {
            context.put(ENTRY, new Entry(method.name(), path, query, sitePath));
            context.reroute(method, RestPaths.previewPath() + "/page");
        } else {
            context.next();
        }
    }

    /** Whether the query names a post or asks for a preview; {@code post_type} alone names none. */
    static boolean pageParams(ObjectNode query) {
        for (String name : PAGE_PARAMS) {
            if (query.has(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithIndex(String path) {
        String index = "/index.php";
        return path.length() >= index.length()
                && RestPaths.startsWithIgnoringAsciiCase(path.substring(path.length() - index.length()), index);
    }

    private static boolean samePath(String path, String sitePath) {
        return path.length() == sitePath.length() && RestPaths.startsWithIgnoringAsciiCase(path, sitePath);
    }
}
