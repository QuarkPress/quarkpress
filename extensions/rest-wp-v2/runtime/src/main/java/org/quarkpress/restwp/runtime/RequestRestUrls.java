package org.quarkpress.restwp.runtime;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.restwp.RestUrls;

/**
 * The current site's REST URLs: the site's domain and path (the request's host and {@code /} when no site is
 * resolved), {@code https} when the request came over https for that host, else {@code http}.
 */
@RequestScoped
public class RequestRestUrls implements RestUrls {

    @Inject
    SiteContext site;

    @Inject
    RestWpConfig config;

    @Inject
    CurrentVertxRequest current;

    @Override
    public String root() {
        return home() + RestPaths.normalizeBase(config.path()).substring(1) + "/";
    }

    @Override
    public String route(String path) {
        return root() + (path.startsWith("/") ? path.substring(1) : path);
    }

    @Override
    public String route(String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(route(path));
        char separator = '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            url.append(separator).append(urlencode(entry.getKey())).append('=').append(urlencode(entry.getValue()));
            separator = '&';
        }
        return url.toString();
    }

    @Override
    public String home() {
        String host = requestHost();
        String domain;
        String path;
        if (site.isResolved()) {
            SiteInfo info = site.site();
            domain = info.domain();
            path = info.path();
        } else {
            domain = host == null ? "localhost" : host;
            path = "/";
        }
        String scheme = secure() && domain.equalsIgnoreCase(host) ? "https" : "http";
        return scheme + "://" + domain + path;
    }

    private HttpServerRequest request() {
        RoutingContext context = current.getCurrent();
        return context == null ? null : context.request();
    }

    private String requestHost() {
        HttpServerRequest request = request();
        HostAndPort authority = request == null ? null : request.authority();
        return authority == null ? null : authority.host();
    }

    private boolean secure() {
        HttpServerRequest request = request();
        return request != null && "https".equals(request.scheme());
    }

    /** PHP's urlencode: letters, digits and {@code -_.} as they are, space as {@code +}, other bytes as %XX. */
    static String urlencode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.') {
                out.write(c);
            } else if (c == ' ') {
                out.write('+');
            } else {
                out.write('%');
                out.write("0123456789ABCDEF".charAt(c >> 4));
                out.write("0123456789ABCDEF".charAt(c & 0xF));
            }
        }
        return out.toString(StandardCharsets.US_ASCII);
    }
}
