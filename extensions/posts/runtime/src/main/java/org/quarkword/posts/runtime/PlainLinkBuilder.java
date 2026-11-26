package org.quarkword.posts.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.DefaultBean;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteContext;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.posts.LinkBuilder;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.TermQueryVariables;

/**
 * WordPress's links with an empty permalink structure: every link is the home URL followed by {@code /?} and a
 * query string. The home and site URLs come from the {@code home} and {@code siteurl} options, or from the site
 * record with the {@code http} scheme when a site has no such option. As WordPress's {@code home_url}, {@code site_url}
 * and {@code admin_url}, they take the request's scheme: on an https request every link is https; otherwise the home
 * keeps its stored scheme, the site URL is http, and the admin URL is https only when the site URL is stored with
 * https. The request's scheme is the HTTP request's after the proxy headers the server trusts; outside a request
 * nothing is https. A term link uses the taxonomy's query variable
 * ({@link TermQueryVariables}, from the taxonomy module); a taxonomy without one, or any taxonomy but the built-in ones
 * when that module is absent, names the taxonomy and the term.
 */
@DefaultBean
@ApplicationScoped
public class PlainLinkBuilder implements LinkBuilder {

    /** The statuses whose links are plain whatever the type (the sample form included). */
    static final Set<String> PLAIN_STATUSES = Set.of("draft", "pending", "auto-draft", "future");

    @Inject
    SiteContext site;

    @Inject
    PostTypes types;

    @Inject
    Instance<TermQueryVariables> queryVariables;

    @Inject
    Instance<CurrentVertxRequest> requests;

    @Override
    public String home() {
        String stored = address("home");
        return isSsl() ? withScheme(stored, "https") : stored;
    }

    @Override
    public String siteUrl() {
        return withScheme(address("siteurl"), isSsl() ? "https" : "http");
    }

    @Override
    public String admin(String path) {
        String stored = address("siteurl");
        boolean https = isSsl() || stored.startsWith("https://");
        return withScheme(stored, https ? "https" : "http") + "/wp-admin/" + withoutLeadingSlashes(path);
    }

    @Override
    public String rest(String route) {
        return home() + "/index.php?rest_route=/" + withoutLeadingSlashes(route);
    }

    @Override
    public String post(PostSnapshot post) {
        long id = post.id().value();
        return switch (post.type()) {
            case "post" -> home() + "/?p=" + id;
            case "page" -> home() + "/?page_id=" + id;
            case "attachment" -> home() + "/?attachment_id=" + id;
            default -> {
                Optional<String> queryVar = types.find(post.type()).flatMap(PostTypeDefinition::queryVar);
                yield queryVar.isPresent()
                                && !PLAIN_STATUSES.contains(post.status())
                                && !post.slug().isEmpty()
                        ? home() + "/?" + queryVar.get() + "=" + post.slug()
                        : home() + "/?post_type=" + post.type() + "&p=" + id;
            }
        };
    }

    @Override
    public String postSample(PostSnapshot post) {
        return post(post);
    }

    @Override
    public String term(String taxonomy, TermId term, String slug) {
        return switch (taxonomy) {
            case "category" -> home() + "/?cat=" + term.value();
            case "post_tag" -> home() + "/?tag=" + slug;
            case "post_format" -> home() + "/?post_format=" + slug;
            default -> {
                Optional<String> queryVar =
                        queryVariables.isResolvable() ? queryVariables.get().of(taxonomy) : Optional.empty();
                yield queryVar.isPresent()
                        ? home() + "/?" + queryVar.get() + "=" + slug
                        : home() + "/?taxonomy=" + taxonomy + "&term=" + slug;
            }
        };
    }

    @Override
    public String author(UserId author) {
        return home() + "/?author=" + author.value();
    }

    @Override
    public Optional<String> postTypeArchive(String type) {
        if (type.equals("post")) {
            long postsPage = site.options().number("page_for_posts", 0);
            boolean staticFront = site.options().string("show_on_front", "").equals("page");
            return Optional.of(staticFront && postsPage > 0 ? home() + "/?page_id=" + postsPage : home());
        }
        return types.find(type)
                .filter(PostTypeDefinition::hasArchive)
                .map(definition -> home() + "/?post_type=" + definition.key());
    }

    @Override
    public String postFormat(String format) {
        return home() + "/?post_format=" + format;
    }

    @Override
    public String date(int year, OptionalInt month, OptionalInt day) {
        StringBuilder value = new StringBuilder(home()).append("/?m=").append(String.format(Locale.ROOT, "%04d", year));
        month.ifPresent(m -> value.append(String.format(Locale.ROOT, "%02d", m)));
        if (month.isPresent()) {
            day.ifPresent(d -> value.append(String.format(Locale.ROOT, "%02d", d)));
        }
        return value.toString();
    }

    @Override
    public String search(String query) {
        return home() + "/?s=" + Slugs.phpUrlencode(query);
    }

    /** The option {@code name} without trailing slash, or the site record's {@code http} address without it. */
    private String address(String name) {
        String stored = site.options().string(name, "");
        String url = stored.isEmpty() ? site.site().url("http").toString() : stored;
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /** Whether the current HTTP request came over https; false outside a request. */
    private boolean isSsl() {
        if (!Arc.container().requestContext().isActive() || !requests.isResolvable()) {
            return false;
        }
        RoutingContext context = requests.get().getCurrent();
        return context != null && "https".equals(context.request().scheme());
    }

    /**
     * {@code url} with {@code scheme} in place of its own; a protocol-relative {@code //host/...} gets it too, and a
     * value without a scheme is left as it is.
     */
    private static String withScheme(String url, String scheme) {
        if (url.startsWith("//")) {
            return scheme + ":" + url;
        }
        int separator = url.indexOf("://");
        if (separator <= 0) {
            return url;
        }
        for (int i = 0; i < separator; i++) {
            char c = url.charAt(i);
            boolean word = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!word) {
                return url;
            }
        }
        return scheme + url.substring(separator);
    }

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }
}
