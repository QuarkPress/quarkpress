package org.quarkword.posts.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
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

/**
 * WordPress's links with an empty permalink structure: every link is the home URL followed by {@code /?} and a
 * query string. The home and site URLs come from the {@code home} and {@code siteurl} options, or from the site
 * record with the {@code http} scheme when a site has no such option. Only the built-in taxonomies' query variables
 * are known here; another taxonomy's term links name the taxonomy and the term.
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

    @Override
    public String home() {
        return address("home");
    }

    @Override
    public String siteUrl() {
        return address("siteurl");
    }

    @Override
    public String admin(String path) {
        return siteUrl() + "/wp-admin/" + withoutLeadingSlashes(path);
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
            default -> home() + "/?taxonomy=" + taxonomy + "&term=" + slug;
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

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }
}
