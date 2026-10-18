package org.quarkpress.multisite.runtime;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import org.eclipse.microprofile.config.ConfigProvider;
import org.quarkpress.core.SiteRequest;

/**
 * What a resolver sees of an HTTP request: scheme, host and port, and the request target exactly as received (path and
 * query, not decoded and with dot segments kept: the router's normalized path is not what a site is looked up for).
 * The host is the request's authority: the {@code Host} header, or the host a proxy forwarded
 * when Quarkus's proxy configuration ({@code quarkus.http.proxy.*}) honours it ({@code X-Forwarded-Host} with
 * {@code enable-forwarded-host}, a {@code Forwarded} header's {@code host=} with {@code allow-forwarded}); the HTTP
 * layer applies that before this runs. An absolute-form target ({@code GET http://host/path}) must name the {@code Host}
 * header's host ({@link #hostsAgree}); the target alone never chooses the site. A target that is neither origin-form nor
 * absolute-form (asterisk-form, authority-form, malformed) yields {@link SiteRequest#none()}: no site.
 */
final class SiteRequests {

    private static final boolean PROXY_FORWARDING = ConfigProvider.getConfig()
            .getOptionalValue("quarkus.http.proxy.proxy-address-forwarding", Boolean.class)
            .orElse(false);

    private SiteRequests() {}

    static SiteRequest of(HttpServerRequest request) {
        String scheme = request.scheme() == null ? "" : request.scheme();
        String uri = request.uri();
        if (uri == null || uri.isEmpty()) {
            return SiteRequest.none();
        }
        if (uri.charAt(0) == '/') {
            HostAndPort authority = request.authority();
            return of(
                    scheme,
                    authority == null ? null : authority.host(),
                    authority == null ? -1 : authority.port(),
                    uri);
        }
        if (uri.indexOf("://") <= 0 || !hostsAgree(request)) {
            return SiteRequest.none();
        }
        HostAndPort authority = request.authority();
        return of(
                scheme,
                authority == null ? null : authority.host(),
                authority == null ? -1 : authority.port(),
                target(uri));
    }

    /**
     * Whether the request's target and its {@code Host} header name the same host: always for an origin-form target;
     * for an absolute-form target, when its authority equals the request's {@code Host} header after {@link SiteAddresses#requestHost}
     * normalization (port ignored). With {@code quarkus.http.proxy.proxy-address-forwarding} on, an absolute-form target is
     * never accepted (the client's own Host is no longer visible). A request that fails this is refused, not resolved.
     */
    static boolean hostsAgree(HttpServerRequest request) {
        String uri = request.uri();
        if (uri == null || uri.isEmpty() || uri.charAt(0) == '/') {
            return true;
        }
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd <= 0) {
            return true;
        }
        String rest = uri.substring(schemeEnd + 3);
        int pathStart = firstOf(rest, '/', '?', '#');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (PROXY_FORWARDING) {
            // Quarkus's proxy handling replaces the Host the client sent with the forwarded one before any filter runs,
            // so the two cannot be compared; a proxy sends origin-form targets anyway
            return false;
        }
        HostAndPort named;
        try {
            named = authority.isEmpty() ? null : HostAndPort.parseAuthority(authority, -1);
        } catch (RuntimeException e) {
            named = null;
        }
        HostAndPort sent = request.authority();
        return named != null
                && sent != null
                && SiteAddresses.requestHost(named.host()).equals(SiteAddresses.requestHost(sent.host()));
    }

    private static SiteRequest of(String scheme, String host, int port, String path) {
        int validPort = port < -1 || port > 65535 ? -1 : port;
        return new SiteRequest(scheme, host == null ? "" : host, validPort, path);
    }

    private static int firstOf(String text, char a, char b, char c) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == a || ch == b || ch == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The path and query of a request target: an origin-form target as it is, an absolute-form one
     * ({@code http://host/path}) without scheme and authority, anything else empty.
     */
    static String target(String uri) {
        if (uri == null) {
            return "";
        }
        if (uri.isEmpty() || uri.charAt(0) == '/') {
            return uri;
        }
        int scheme = uri.indexOf("://");
        if (scheme > 0) {
            int path = uri.indexOf('/', scheme + 3);
            int query = uri.indexOf('?', scheme + 3);
            if (path >= 0 && (query < 0 || path < query)) {
                return uri.substring(path);
            }
            return query >= 0 ? "/" + uri.substring(query) : "";
        }
        return "";
    }
}
