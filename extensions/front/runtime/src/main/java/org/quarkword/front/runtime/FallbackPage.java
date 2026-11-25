package org.quarkword.front.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.ContentFilter;
import org.quarkword.core.Filters;
import org.quarkword.core.RenderContext;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.core.TitleFilter;
import org.quarkword.front.FrontAnswer;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRenderer;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.Permalinks;
import org.quarkword.front.Viewer;
import org.quarkword.posts.FeaturedImages;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.taxonomy.PostFormats;

/**
 * The front end's own renderer while no theme provides one: a minimal HTML page. A singular request shows the site's
 * name, the post's title, its date and its content through the registered title and content filters (an empty
 * {@code entry-content} when the post's password is required); a list (the home, an archive, a search) the titles of
 * its posts linked to their permalinks, with links to the previous and next page of the list when they exist; a request
 * that was not found (its flags say so, or its status is 404) {@value #NOT_FOUND}. Texts are HTML-escaped (an entity
 * already written is kept); the content is HTML as the filters leave it. The body's classes name the page as
 * WordPress's do ({@code single single-<type> postid-<id>}, {@code home blog}, {@code archive}, {@code search},
 * {@code error404}).
 */
@DefaultBean
@ApplicationScoped
public class FallbackPage implements FrontRenderer {

    /** The heading of a request that was not found. */
    static final String NOT_FOUND = "Page not found";

    private static final DateTimeFormatter MACHINE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    /** A post as the page shows it: the title already filtered, the content already filtered (empty when locked). */
    record Singular(
            String siteName,
            String title,
            String type,
            long id,
            String format,
            ZonedDateTime date,
            long thumbnail,
            Optional<String> content) {

        Singular {
            Objects.requireNonNull(siteName, "siteName");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(content, "content");
        }
    }

    /** A list item: the post's link and its filtered title. */
    record Item(String link, String title) {}

    private record Rendering(SiteId site) implements RenderContext {}

    @Inject
    SiteContext site;

    @Inject
    Permalinks permalinks;

    @Inject
    PostFormats formats;

    @Inject
    CurrentVertxRequest current;

    @Inject
    @All
    List<InstanceHandle<TitleFilter>> titleHandles;

    @Inject
    @All
    List<InstanceHandle<ContentFilter>> contentHandles;

    private List<TitleFilter> titleFilters;
    private List<ContentFilter> contentFilters;

    @PostConstruct
    void order() {
        titleFilters = Filters.ordered(titleHandles);
        contentFilters = Filters.ordered(contentHandles);
    }

    @Override
    public FrontAnswer render(FrontRequest request, Viewer viewer, PasswordState password) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(password, "password");
        String siteName = site.options().string("blogname", "");
        FrontFlags flags = request.flags();
        String html;
        // a 404 decided before the flags say so (the variables of pages not served yet) is a not-found page too
        if (flags.is404()
                || request.status() == 404
                || (flags.singular() && request.shownPost().isEmpty())) {
            html = notFound(siteName);
        } else if (flags.singular()) {
            html = singular(singularOf(request.shownPost().get(), request, siteName, password));
        } else {
            html = list(siteName, bodyClass(flags), items(request), pageLink(request, -1), pageLink(request, 1));
        }
        return new FrontAnswer(request.status(), Map.of(), html.getBytes(StandardCharsets.UTF_8));
    }

    private Singular singularOf(PostSnapshot post, FrontRequest request, String siteName, PasswordState password) {
        Rendering rendering = new Rendering(post.site());
        String title = Filters.apply(titleFilters, post.title(), (filter, value) -> filter.apply(value, rendering));
        Optional<String> content = password == PasswordState.NOT_REQUIRED
                ? Optional.of(Filters.apply(
                        contentFilters, post.content(), (filter, value) -> filter.apply(value, rendering)))
                : Optional.empty();
        String format =
                post.metaText("post_format").filter(value -> !value.isEmpty()).orElseGet(() -> formats.of(post.id()));
        return new Singular(
                siteName,
                title,
                post.type(),
                post.id().value(),
                format,
                request.shownAt().atZone(site.zone()),
                FeaturedImages.of(post),
                content);
    }

    private List<Item> items(FrontRequest request) {
        List<Item> items = new ArrayList<>();
        for (PostSnapshot post : request.posts().posts()) {
            Rendering rendering = new Rendering(post.site());
            String title = Filters.apply(titleFilters, post.title(), (filter, value) -> filter.apply(value, rendering));
            items.add(new Item(permalinks.post(post), title));
        }
        return items;
    }

    /** The link to the list page before ({@code step} -1) or after ({@code step} 1) the shown one, when it exists. */
    private Optional<String> pageLink(FrontRequest request, int step) {
        int shown = Math.max(1, request.paged());
        long target = (long) shown + step;
        if (target < 1 || target > request.posts().maxPages()) {
            return Optional.empty();
        }
        return Optional.of(permalinks.paged(requestTarget(), (int) target));
    }

    /** The request's path and query as sent, from the front end's entry; {@code /} outside a front-end request. */
    private String requestTarget() {
        RoutingContext context = current.getCurrent();
        if (context != null && context.get(FrontRoute.ENTRY) instanceof FrontRoute.Entry entry) {
            return entry.target();
        }
        return "/";
    }

    private static String bodyClass(FrontFlags flags) {
        if (flags.search()) {
            return "search";
        }
        if (flags.home()) {
            return "home blog";
        }
        return "archive";
    }

    // ---- the HTML -------------------------------------------------------------------------------------------------

    /** The page of one post. */
    static String singular(Singular post) {
        StringBuilder html = new StringBuilder();
        head(html, post.title(), post.siteName());
        html.append("<body class=\"single single-")
                .append(escape(post.type()))
                .append(" postid-")
                .append(post.id())
                .append(" single-format-")
                .append(escape(post.format()))
                .append("\">\n");
        header(html, post.siteName());
        html.append("<main>\n<article id=\"post-").append(post.id()).append("\">\n");
        html.append("<h1 class=\"entry-title\">").append(escape(post.title())).append("</h1>\n");
        html.append("<p class=\"entry-date\"><time datetime=\"")
                .append(MACHINE_DATE.format(post.date()))
                .append("\">")
                .append(HUMAN_DATE.format(post.date()))
                .append("</time></p>\n");
        if (post.thumbnail() > 0) {
            html.append("<figure class=\"post-thumbnail\" data-attachment-id=\"")
                    .append(post.thumbnail())
                    .append("\"></figure>\n");
        }
        html.append("<div class=\"entry-content\">")
                .append(post.content().orElse(""))
                .append("</div>\n");
        html.append("</article>\n</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /** A list of posts with the links to its previous and next page. */
    static String list(
            String siteName, String bodyClass, List<Item> items, Optional<String> previous, Optional<String> next) {
        StringBuilder html = new StringBuilder();
        head(html, "", siteName);
        html.append("<body class=\"").append(bodyClass).append("\">\n");
        header(html, siteName);
        html.append("<main>\n<ul class=\"posts\">\n");
        for (Item item : items) {
            html.append("<li><a href=\"")
                    .append(escape(item.link()))
                    .append("\">")
                    .append(escape(item.title()))
                    .append("</a></li>\n");
        }
        html.append("</ul>\n");
        if (previous.isPresent() || next.isPresent()) {
            html.append("<nav class=\"pagination\">");
            previous.ifPresent(link -> html.append("<a class=\"prev\" href=\"")
                    .append(escape(link))
                    .append("\">Previous page</a>"));
            next.ifPresent(link -> html.append("<a class=\"next\" href=\"")
                    .append(escape(link))
                    .append("\">Next page</a>"));
            html.append("</nav>\n");
        }
        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /** The page of a request that was not found. */
    static String notFound(String siteName) {
        StringBuilder html = new StringBuilder();
        head(html, NOT_FOUND, siteName);
        html.append("<body class=\"error404\">\n");
        header(html, siteName);
        html.append("<main>\n<h1 class=\"entry-title\">")
                .append(NOT_FOUND)
                .append("</h1>\n</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /** The document's head: the title (the site's name alone when the title is empty) and the charset. */
    private static void head(StringBuilder html, String title, String siteName) {
        html.append("<!DOCTYPE html>\n<html lang=\"en-US\">\n<head>\n<meta charset=\"UTF-8\">\n<title>");
        String plain = title.strip();
        if (!plain.isEmpty()) {
            html.append(escape(plain)).append(" &#8211; ");
        }
        html.append(escape(siteName)).append("</title>\n</head>\n");
    }

    private static void header(StringBuilder html, String siteName) {
        html.append("<header class=\"site-header\"><p class=\"site-title\">")
                .append(escape(siteName))
                .append("</p></header>\n");
    }

    /**
     * The text with {@code <}, {@code >}, {@code "} and {@code '} written as entities, and every {@code &} that does
     * not already start an entity ({@code &name;}, {@code &#123;}, {@code &#x1f;}) as {@code &amp;}, as WordPress's
     * {@code esc_html} writes it.
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                case '&' -> out.append(entityAt(text, i) ? "&" : "&amp;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Whether an entity starts at {@code at} (an {@code &}): a name, a decimal or a hexadecimal number, then {@code ;}. */
    private static boolean entityAt(String text, int at) {
        int i = at + 1;
        int start;
        if (i < text.length() && text.charAt(i) == '#') {
            i++;
            boolean hex = i < text.length() && (text.charAt(i) == 'x' || text.charAt(i) == 'X');
            if (hex) {
                i++;
            }
            start = i;
            while (i < text.length() && i - start < 10 && (hex ? hexDigit(text.charAt(i)) : digit(text.charAt(i)))) {
                i++;
            }
        } else {
            start = i;
            while (i < text.length() && i - start < 32 && (letter(text.charAt(i)) || digit(text.charAt(i)))) {
                i++;
            }
            if (i > start && !letter(text.charAt(start))) {
                return false;
            }
        }
        return i > start && i < text.length() && text.charAt(i) == ';';
    }

    private static boolean letter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean hexDigit(char c) {
        return digit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
