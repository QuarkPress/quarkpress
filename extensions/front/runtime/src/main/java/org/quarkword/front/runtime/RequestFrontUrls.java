package org.quarkword.front.runtime;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkword.core.SiteContext;
import org.quarkword.front.FrontUrls;

/**
 * The URLs of the current request's site from its {@code home} and {@code siteurl} options; a site without such an
 * option uses its record's {@code http} address, as the posts module's plain links do. The request's scheme comes
 * from the HTTP request after the proxy headers the server trusts; outside a request nothing is https. The REST prefix
 * is the REST module's {@code quarkword.rest-wp-v2.path} when that module is installed, else {@code /wp-json}.
 */
@RequestScoped
public class RequestFrontUrls implements FrontUrls {

    private static final String DEFAULT_REST_PREFIX = "/wp-json";

    @Inject
    SiteContext site;

    @Inject
    FrontConfig config;

    @Inject
    CurrentVertxRequest current;

    /** The key the multisite module gates its beans on: when true, the home and siteurl settings are ignored. */
    @ConfigProperty(name = "quarkword.multisite.enabled", defaultValue = "false")
    boolean multisite;

    @ConfigProperty(name = "quarkword.rest-wp-v2.path")
    Optional<String> restPath;

    @Override
    public String home() {
        String stored = address("home", config.home());
        return isSsl() ? UrlSchemes.set(stored, "https", true, false) : stored;
    }

    @Override
    public String home(String path) {
        return withPath(home(), path);
    }

    @Override
    public String homePath() {
        String path = URI.create(home()).getRawPath();
        if (path == null) {
            return "";
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        int end = path.length();
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    @Override
    public String siteUrl() {
        return UrlSchemes.set(storedSiteUrl(), null, isSsl(), forceSslAdmin());
    }

    @Override
    public String siteUrl(String path) {
        return withPath(siteUrl(), path);
    }

    @Override
    public String admin(String path) {
        return withPath(adminRoot("admin") + "/wp-admin/", path);
    }

    @Override
    public String login() {
        return adminRoot("login") + "/wp-login.php";
    }

    @Override
    public String rest(String route) {
        String path = "/" + withoutLeadingSlashes(route);
        String structure = site.options().string("permalink_structure", "");
        if (structure.isEmpty()) {
            return home() + "/index.php?rest_route=" + path;
        }
        String prefix = restPath.map(RequestFrontUrls::normalizedPrefix).orElse(DEFAULT_REST_PREFIX);
        return home() + (structure.startsWith("/index.php") ? "/index.php" : "") + prefix + path;
    }

    @Override
    public boolean forceSslAdmin() {
        return config.forceSslAdmin() || storedSiteUrl().startsWith("https://");
    }

    @Override
    public boolean isSsl() {
        RoutingContext context = current.getCurrent();
        return context != null && "https".equals(context.request().scheme());
    }

    private String adminRoot(String mode) {
        return UrlSchemes.set(storedSiteUrl(), mode, isSsl(), forceSslAdmin());
    }

    private String storedSiteUrl() {
        return address("siteurl", config.siteurl());
    }

    /**
     * The setting on a single site, else the option {@code name}, else the site record's {@code http} address; without
     * trailing slashes.
     */
    private String address(String name, Optional<String> setting) {
        if (!multisite && setting.isPresent()) {
            return withoutTrailingSlashes(setting.get().trim());
        }
        String stored = site.options().string(name, "");
        String url = stored.isEmpty() ? site.site().url("http").toString() : stored;
        return withoutTrailingSlashes(url);
    }

    /** {@code root} followed by {@code /} and {@code path} without its leading slashes; an empty path adds nothing. */
    private static String withPath(String root, String path) {
        if (path.isEmpty()) {
            return root;
        }
        return root.endsWith("/") ? root + withoutLeadingSlashes(path) : root + "/" + withoutLeadingSlashes(path);
    }

    /** The REST base with one leading slash and no trailing slash: {@code wp-json/} and {@code /wp-json} agree. */
    private static String normalizedPrefix(String path) {
        String prefix = withoutTrailingSlashes(withoutLeadingSlashes(path.trim()));
        return prefix.isEmpty() ? DEFAULT_REST_PREFIX : "/" + prefix;
    }

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
