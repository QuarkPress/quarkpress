package org.quarkword.restwp.runtime;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.quarkword.core.SiteContext;
import org.quarkword.posts.LinkBuilder;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.restwp.RestUrls;

/**
 * The current site's REST URLs, built on its home URL as WordPress's {@code rest_url} builds them: the posts module's
 * {@link LinkBuilder#home()} followed by the configured base, {@code https} when the request came over https. With no
 * site resolved, the request's host and {@code /} stand for the home.
 */
@RequestScoped
public class RequestRestUrls implements RestUrls {

    @Inject
    SiteContext site;

    @Inject
    RestWpConfig config;

    @Inject
    LinkBuilder links;

    @Inject
    CurrentVertxRequest current;

    @Override
    public String root() {
        return home() + RestPaths.normalizeBase(config.path()) + "/";
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
            url.append(separator)
                    .append(Slugs.phpUrlencode(entry.getKey()))
                    .append('=')
                    .append(Slugs.phpUrlencode(entry.getValue()));
            separator = '&';
        }
        return url.toString();
    }

    /** The home URL without trailing slash, in the request's scheme when that is https. */
    private String home() {
        if (!site.isResolved()) {
            String host = requestHost();
            return (secure() ? "https" : "http") + "://" + (host == null ? "localhost" : host);
        }
        String home = links.home();
        int scheme = home.indexOf("://");
        return secure() && scheme > 0 ? "https" + home.substring(scheme) : home;
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
}
