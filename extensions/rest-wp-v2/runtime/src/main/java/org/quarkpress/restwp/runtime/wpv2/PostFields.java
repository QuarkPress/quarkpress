package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.ExcerptFilter;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TitleFilter;
import org.quarkpress.core.UserId;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * A post's fields as WordPress's posts controller prepares them: the selected fields of the type in WordPress's
 * order, filtered by the context. Dates are the site's local time and GMT; a floating draft's GMT date is its local
 * date converted with the site's zone. The rendered title, content and excerpt run the registered filter chains (an
 * empty excerpt is the content's text cut to {@code excerpt_length} words, {@value #DEFAULT_EXCERPT_LENGTH} by
 * default, followed by WordPress's ellipsis); a post with a password renders its content and excerpt as empty text.
 * The link is the post's plain permalink.
 */
@ApplicationScoped
class PostFields {

    static final int DEFAULT_EXCERPT_LENGTH = 55;

    static final String EXCERPT_MORE = " [&hellip;]";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String BLOCK_DELIMITER = "<!-- wp:";

    /** The statuses whose link is the plain id form even for a type with a query variable. */
    private static final Set<String> DRAFT_LIKE = Set.of("draft", "pending", "auto-draft", "future");

    private record Rendering(SiteId site) implements RenderContext {}

    @Inject
    SiteContext site;

    @Inject
    RestUrls urls;

    @Inject
    Terms terms;

    @Inject
    PostFormats formats;

    @Inject
    MetaFields meta;

    @Inject
    @All
    List<InstanceHandle<TitleFilter>> titleHandles;

    @Inject
    @All
    List<InstanceHandle<ContentFilter>> contentHandles;

    @Inject
    @All
    List<InstanceHandle<ExcerptFilter>> excerptHandles;

    private List<TitleFilter> titleFilters;
    private List<ContentFilter> contentFilters;
    private List<ExcerptFilter> excerptFilters;

    @PostConstruct
    void order() {
        titleFilters = Filters.ordered(titleHandles);
        contentFilters = Filters.ordered(contentHandles);
        excerptFilters = Filters.ordered(excerptHandles);
    }

    /** The post's selected fields in WordPress's order, filtered by {@code context}; links are added later. */
    RestResponse prepare(
            PostRoutes routes, PostSnapshot post, Fields.Selection fields, String context, RestRequest request) {
        ZoneId zone = site.zone();
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.includes("id")) {
            data.put("id", post.id().value());
        }
        LocalDateTime localDate = post.localDate(zone);
        if (fields.includes("date")) {
            data.put("date", DATE.format(localDate));
        }
        if (fields.includes("date_gmt")) {
            Instant gmt = post.publishedAt()
                    .orElseGet(() -> ZonedDateTime.of(localDate, zone).toInstant());
            data.put("date_gmt", gmt(gmt));
        }
        if (fields.includes("guid")) {
            ObjectNode guid = data.putObject("guid");
            if (fields.includes("guid.rendered")) {
                guid.put("rendered", post.guid());
            }
            if (fields.includes("guid.raw")) {
                guid.put("raw", post.guid());
            }
        }
        if (fields.includes("modified")) {
            data.put("modified", DATE.format(post.localModified(zone)));
        }
        if (fields.includes("modified_gmt")) {
            data.put("modified_gmt", gmt(post.modifiedAt()));
        }
        if (fields.includes("password")) {
            data.put("password", post.password());
        }
        if (fields.includes("slug")) {
            data.put("slug", post.slug());
        }
        if (fields.includes("status")) {
            data.put("status", post.status());
        }
        if (fields.includes("type")) {
            data.put("type", post.type());
        }
        if (fields.includes("link")) {
            data.put("link", link(routes.type(), post));
        }
        boolean hidden = post.passwordProtected();
        Rendering rendering = new Rendering(post.site());
        if (routes.has("title") && fields.includes("title")) {
            ObjectNode title = data.putObject("title");
            if (fields.includes("title.raw")) {
                title.put("raw", post.title());
            }
            if (fields.includes("title.rendered")) {
                title.put(
                        "rendered",
                        Filters.apply(titleFilters, post.title(), (filter, value) -> filter.apply(value, rendering)));
            }
        }
        if (routes.has("content") && fields.includes("content")) {
            ObjectNode content = data.putObject("content");
            if (fields.includes("content.raw")) {
                content.put("raw", post.content());
            }
            if (fields.includes("content.rendered")) {
                content.put(
                        "rendered",
                        hidden
                                ? ""
                                : Filters.apply(
                                        contentFilters,
                                        post.content(),
                                        (filter, value) -> filter.apply(value, rendering)));
            }
            if (fields.includes("content.protected")) {
                content.put("protected", hidden);
            }
            if (fields.includes("content.block_version")) {
                content.put("block_version", post.content().contains(BLOCK_DELIMITER) ? 1 : 0);
            }
        }
        if (routes.has("excerpt") && fields.includes("excerpt")) {
            ObjectNode excerpt = data.putObject("excerpt");
            if (fields.includes("excerpt.raw")) {
                excerpt.put("raw", post.excerpt());
            }
            if (fields.includes("excerpt.rendered")) {
                excerpt.put("rendered", hidden ? "" : renderedExcerpt(post, rendering, request));
            }
            if (fields.includes("excerpt.protected")) {
                excerpt.put("protected", hidden);
            }
        }
        if (routes.has("author") && fields.includes("author")) {
            data.put("author", post.author().map(UserId::value).orElse(0L));
        }
        if (routes.has("featured_media") && fields.includes("featured_media")) {
            data.put("featured_media", FeaturedImages.of(post));
        }
        if (routes.has("parent") && fields.includes("parent")) {
            data.put("parent", post.parent().map(PostId::value).orElse(0L));
        }
        if (routes.has("menu_order") && fields.includes("menu_order")) {
            data.put("menu_order", post.menuOrder());
        }
        if (routes.has("comment_status") && fields.includes("comment_status")) {
            data.put("comment_status", post.commentStatus());
        }
        if (routes.has("ping_status") && fields.includes("ping_status")) {
            data.put("ping_status", post.pingStatus());
        }
        if (routes.has("sticky") && fields.includes("sticky")) {
            data.put("sticky", post.sticky());
        }
        if (fields.includes("template")) {
            data.put("template", PageTemplates.of(post));
        }
        if (routes.has("format") && fields.includes("format")) {
            data.put("format", formats.of(post.id()));
        }
        if (routes.has("meta") && fields.includes("meta")) {
            data.set("meta", meta.read(post.type(), post));
        }
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            String base = PostRoutes.base(taxonomy);
            if (fields.includes(base)) {
                ArrayNode ids = data.putArray(base);
                for (TermSnapshot term : terms.postTerms(post.id(), taxonomy.key())) {
                    ids.add(term.id().value());
                }
            }
        }
        return RestResponse.ok(ContextFilter.apply(data, routes.schema(), context));
    }

    private static String gmt(Instant instant) {
        return DATE.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * The plain permalink: {@code ?p=<id>} for posts, {@code ?page_id=<id>} for pages, and for another type
     * {@code ?<type>=<slug>} when the type is public and the post is neither a draft, pending, scheduled nor an
     * auto-draft and has a slug, else {@code ?post_type=<type>&p=<id>}.
     */
    String link(PostTypeDefinition type, PostSnapshot post) {
        String home = urls.home();
        return switch (type.key()) {
            case "post" -> home + "?p=" + post.id().value();
            case "page" -> home + "?page_id=" + post.id().value();
            default ->
                type.publicly()
                                && !DRAFT_LIKE.contains(post.status())
                                && !post.slug().isEmpty()
                        ? home + "?" + type.key() + "=" + post.slug()
                        : home + "?post_type=" + type.key() + "&p=" + post.id().value();
        };
    }

    /**
     * The excerpt filters over the stored excerpt, or, when it is empty, over the content's text without tags cut
     * to the request's {@code excerpt_length} words (any source; the default otherwise), followed by the ellipsis
     * when words were cut.
     */
    private String renderedExcerpt(PostSnapshot post, Rendering rendering, RestRequest request) {
        String text = post.excerpt();
        if (text.isEmpty()) {
            int length = request.param("excerpt_length")
                    .filter(value -> !value.isContainerNode())
                    .map(value -> (int) Math.max(0, Math.min(Integer.MAX_VALUE, PhpValues.toLong(value))))
                    .orElse(DEFAULT_EXCERPT_LENGTH);
            text = trimWords(Kses.stripAllTags(post.content(), false), length);
        }
        return Filters.apply(excerptFilters, text, (filter, value) -> filter.apply(value, rendering));
    }

    /**
     * WordPress's word trimming: the text split on runs of line feeds, carriage returns, tabs and spaces, the first
     * {@code words} pieces joined with one space, followed by the ellipsis when pieces were dropped.
     */
    static String trimWords(String text, int words) {
        List<String> pieces = new ArrayList<>();
        boolean more = false;
        int i = 0;
        int length = text.length();
        while (i < length) {
            while (i < length && separator(text.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < length && !separator(text.charAt(i))) {
                i++;
            }
            if (i > start) {
                if (pieces.size() == words) {
                    more = true;
                    break;
                }
                pieces.add(text.substring(start, i));
            }
        }
        String joined = String.join(" ", pieces);
        return more ? joined + EXCERPT_MORE : joined;
    }

    private static boolean separator(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
