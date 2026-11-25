package org.quarkword.front.runtime;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import java.util.Objects;

/**
 * The front end's catch-all route: registered on every path after Quarkus REST's routes, it runs only for a request
 * that no filter answered, no static file matched and no resource or other host route took. A {@code GET} or
 * {@code HEAD} outside Quarkus's non-application root ({@code /q/} by default) is then kept on the routing context as
 * an {@link Entry} (its method, raw path and query, host and scheme as sent) and re-routed to the internal page
 * resource ({@link FrontPage}), which renders it inside a normal request (authentication, request scope and site
 * binding active). Every other request is passed on, so the server's own 404 or 405 answers it: the front end never
 * refuses a method.
 *
 * <p>A host application that maps {@code NotFoundException} (or one of its supertypes) with a JAX-RS
 * {@code ExceptionMapper} makes Quarkus REST answer unmatched paths itself, and this route then never runs.
 */
public final class FrontRoute implements Handler<RoutingContext> {

    /** Where the request is kept on the routing context; the page resource serves only requests that carry it. */
    public static final String ENTRY = "org.quarkword.front.entry";

    /** The route's order: after Quarkus REST's routes (20 500) and the static resources (10 000). */
    public static final int ORDER = 21_000;

    /**
     * A front-end request as it arrived: {@code method} {@code GET} or {@code HEAD}, {@code rawPath} the path
     * percent-encoded as sent, {@code rawQuery} the query string as sent (empty when there is none), {@code host} the
     * request's host with its port when one was given, {@code scheme} after the proxy headers the server trusts.
     */
    public record Entry(String method, String rawPath, String rawQuery, String host, String scheme) {

        public Entry {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(rawPath, "rawPath");
            Objects.requireNonNull(rawQuery, "rawQuery");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(scheme, "scheme");
        }

        /** The request target: the raw path, then {@code ?} and the raw query when there is one. */
        public String target() {
            return rawQuery.isEmpty() ? rawPath : rawPath + "?" + rawQuery;
        }
    }

    private final String entryPath;
    private final String nonApplicationRoot;

    /**
     * @param entryPath the absolute path of the page resource's {@code page} method
     * @param nonApplicationRoot Quarkus's non-application root path ({@code /q/} by default); {@code /} or empty when
     *     the non-application endpoints share the application's root
     */
    public FrontRoute(String entryPath, String nonApplicationRoot) {
        this.entryPath = Objects.requireNonNull(entryPath, "entryPath");
        this.nonApplicationRoot = normalizeRoot(Objects.requireNonNull(nonApplicationRoot, "nonApplicationRoot"));
    }

    @Override
    public void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        HttpMethod method = request.method();
        if ((method != HttpMethod.GET && method != HttpMethod.HEAD)
                || context.get(ENTRY) != null
                || underNonApplicationRoot(context.normalizedPath())) {
            context.next();
            return;
        }
        String path = request.path() == null || request.path().isEmpty() ? "/" : request.path();
        String query = request.query() == null ? "" : request.query();
        String scheme = request.scheme() == null ? "http" : request.scheme();
        context.put(ENTRY, new Entry(method.name(), path, query, host(request), scheme));
        context.reroute(method, entryPath);
    }

    /** The request's host as sent, with its port when one was given; empty when the request named none. */
    static String host(HttpServerRequest request) {
        HostAndPort authority = request.authority();
        if (authority == null || authority.host() == null) {
            return "";
        }
        return authority.port() >= 0 ? authority.host() + ":" + authority.port() : authority.host();
    }

    /** Whether a normalized path lies under the non-application root. */
    boolean underNonApplicationRoot(String path) {
        return !nonApplicationRoot.isEmpty() && path != null && (path + "/").startsWith(nonApplicationRoot);
    }

    /** The root with one leading slash and one trailing slash; empty when it is the application's root. */
    static String normalizeRoot(String path) {
        String leading = path.startsWith("/") ? path : "/" + path;
        int end = leading.length();
        while (end > 1 && leading.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = leading.substring(0, end);
        return trimmed.equals("/") ? "" : trimmed + "/";
    }
}
