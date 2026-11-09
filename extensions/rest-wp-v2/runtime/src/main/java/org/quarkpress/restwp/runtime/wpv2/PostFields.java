package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * A post's fields as WordPress's posts controller prepares them: the selected fields of the type in WordPress's
 * order, filtered by the context. Dates are the site's local time and GMT; a floating draft's GMT date is its local
 * date converted with the site's zone. The rendered title, content and excerpt run the registered filter chains (an
 * empty excerpt is the content's text cut to {@code excerpt_length} words, {@value #DEFAULT_EXCERPT_LENGTH} by
 * default, followed by WordPress's ellipsis); a post with a password (a stored {@code 0} or nothing is none, as for
 * WordPress) renders its content and excerpt as empty text unless the request lifted its protection, and says they
 * are protected either way. The link is the post's plain
 * permalink. A stored template {@code default} reads as none. The post's links come with the fields when the
 * request selects them. The derived fields of a viewable public type are the
 * permalink template (the plain link), the slug the title would generate (made unique as for a published post, the
 * post itself never colliding with it) and the front-end class list of the post.
 */
@ApplicationScoped
class PostFields {

    static final int DEFAULT_EXCERPT_LENGTH = 55;

    static final String EXCERPT_MORE = " [&hellip;]";

    /** The stored template that means none. */
    private static final String DEFAULT_TEMPLATE = "default";

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
    Posts posts;

    @Inject
    Taxonomies taxonomies;

    @Inject
    SlugSanitizer slugSanitizer;

    @Inject
    PostLinks links;

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

    /** The post's selected fields in WordPress's order, filtered by {@code context}, with the post's links. */
    RestResponse prepare(
            PostRoutes routes, PostSnapshot post, Fields.Selection fields, String context, RestRequest request) {
        return prepare(routes, post, fields, context, request, false);
    }

    /**
     * The post's selected fields in WordPress's order, filtered by {@code context}, with the post's links when the
     * request selects them.
     *
     * @param lifted whether the request lifted the post's password protection (the edit context of a caller who may
     *     edit it, or a matching password): the content and excerpt are rendered, while the protected flags and the
     *     class list still say the password is there
     */
    RestResponse prepare(
            PostRoutes routes,
            PostSnapshot post,
            Fields.Selection fields,
            String context,
            RestRequest request,
            boolean lifted) {
        ObjectNode data = fields(routes, post, fields, context, request, lifted);
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, routes.schema(), context));
        if (fields.includes("_links") || fields.includes("_embedded")) {
            response = response.withLinks(links.of(routes, post, request));
        }
        return response;
    }

    /**
     * The post's selected fields of the posts controller in WordPress's order, before the context filter and without
     * links: what {@link #prepare} answers, and what a controller that adds fields of its own builds on. The derived
     * fields are skipped in the contexts whose filter would drop them.
     *
     * @param lifted as for {@link #prepare(PostRoutes, PostSnapshot, Fields.Selection, String, RestRequest, boolean)}
     */
    ObjectNode fields(
            PostRoutes routes,
            PostSnapshot post,
            Fields.Selection fields,
            String context,
            RestRequest request,
            boolean lifted) {
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
        boolean protectedPost = PostPasswords.required(post);
        boolean hidden = protectedPost && !lifted;
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
                content.put("protected", protectedPost);
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
                excerpt.put("protected", protectedPost);
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
            String template = PageTemplates.of(post);
            data.put("template", template.equals(DEFAULT_TEMPLATE) ? "" : template);
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
        // the derived fields cost lookups: skipped in the contexts where the filter would drop them
        boolean edit = context.equals("edit");
        if (edit && routes.has("permalink_template") && fields.includes("permalink_template")) {
            data.put("permalink_template", link(routes.type(), post));
        }
        if (edit && routes.has("generated_slug") && fields.includes("generated_slug")) {
            data.put("generated_slug", generatedSlug(post));
        }
        if (!context.equals("embed") && routes.has("class_list") && fields.includes("class_list")) {
            ArrayNode classes = data.putArray("class_list");
            String format = routes.supports("post-formats") ? formats.of(post.id()) : "";
            classList(
                            post,
                            routes,
                            format,
                            PostPasswords.required(post),
                            FeaturedImages.of(post) > 0,
                            publicTerms(post))
                    .forEach(classes::add);
        }
        return data;
    }

    /** {@code text} through the registered content filters, rendered for {@code post}'s site. */
    String renderContent(PostSnapshot post, String text) {
        Rendering rendering = new Rendering(post.site());
        return Filters.apply(contentFilters, text, (filter, value) -> filter.apply(value, rendering));
    }

    /** {@code text} through the registered excerpt filters, rendered for {@code post}'s site. */
    String renderExcerpt(PostSnapshot post, String text) {
        Rendering rendering = new Rendering(post.site());
        return Filters.apply(excerptFilters, text, (filter, value) -> filter.apply(value, rendering));
    }

    /**
     * The {@code password} parameter of a request, from any source, when it is a text that is not empty in PHP's
     * sense: what lifts the protection of the posts it matches.
     */
    static Optional<String> givenPassword(RestRequest request) {
        return request.param("password")
                .filter(JsonNode::isTextual)
                .filter(value -> !PhpValues.isEmpty(value))
                .map(JsonNode::textValue);
    }

    /**
     * The slug the post's title would generate: the title sanitized (the post id when that gives nothing), made
     * unique as for a published post of the type under the post's parent, the post itself never colliding, shown
     * with its percent-encoded bytes decoded (a non-ASCII title's slug reads as its characters, as WordPress shows it).
     */
    private String generatedSlug(PostSnapshot post) {
        String candidate = slugSanitizer.sanitize(
                post.title(),
                new SlugContext(
                        post.site(), Optional.of(post.id()), post.type(), post.status(), post.parent(), post.title()));
        if (candidate.isEmpty()) {
            candidate = Long.toString(post.id().value());
        }
        return percentDecoded(
                posts.uniqueSlug(post.type(), candidate, Optional.of(post.id()), post.parent(), "publish"));
    }

    /**
     * {@code text} with each {@code %} followed by two hexadecimal digits replaced by that byte, the bytes read as
     * UTF-8; any other character is kept. Empty when the bytes are not UTF-8 text.
     */
    static String percentDecoded(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int high = i + 2 < text.length() && c == '%' ? hexDigit(text.charAt(i + 1)) : -1;
            int low = high >= 0 ? hexDigit(text.charAt(i + 2)) : -1;
            if (low >= 0) {
                bytes.write(high * 16 + low);
                i += 3;
                continue;
            }
            int end = i + Character.charCount(text.codePointAt(i));
            byte[] encoded = text.substring(i, end).getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
            i = end;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException notText) {
            // WordPress answers an empty slug when the decoded bytes are not UTF-8 text
            return "";
        }
    }

    /** The value of an ASCII hexadecimal digit, -1 for any other character. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** The post's terms of every public taxonomy of its type, in the taxonomies' registration order. */
    private Map<String, List<TermSnapshot>> publicTerms(PostSnapshot post) {
        Map<String, List<TermSnapshot>> result = new LinkedHashMap<>();
        for (TaxonomyDefinition taxonomy : taxonomies.forType(post.type())) {
            if (taxonomy.publicly()) {
                result.put(taxonomy.key(), terms.postTerms(post.id(), taxonomy.key()));
            }
        }
        return result;
    }

    /**
     * WordPress's front-end classes of a post as REST lists them: {@code post-<id>}, the type, {@code type-<type>},
     * {@code status-<status>}, {@code format-<slug>} for a type with post formats ({@code standard} when the post has
     * none), {@code post-password-required} when the post has a password (a REST lift never changes the list),
     * {@code has-post-thumbnail} for a featured image on a post without password, {@code hentry}, then one class per
     * term of every public taxonomy of the type ({@code category-<slug>}, {@code tag-<slug>}, else
     * {@code <taxonomy>-<slug>}; a slug that is empty or reads as a PHP number is replaced by the term's id), each
     * class once.
     *
     * @param format the post's format slug, or empty for none; ignored for a type without post formats
     * @param publicTerms the post's terms by public taxonomy of the type, in registration order
     */
    static List<String> classList(
            PostSnapshot post,
            PostRoutes routes,
            String format,
            boolean passwordRequired,
            boolean hasThumbnail,
            Map<String, List<TermSnapshot>> publicTerms) {
        Set<String> classes = new LinkedHashSet<>();
        classes.add("post-" + post.id().value());
        classes.add(post.type());
        classes.add("type-" + post.type());
        classes.add("status-" + post.status());
        if (routes.supports("post-formats")) {
            classes.add("format-" + (format.isEmpty() ? "standard" : format));
        }
        if (passwordRequired) {
            classes.add("post-password-required");
        }
        if (hasThumbnail && !passwordRequired) {
            classes.add("has-post-thumbnail");
        }
        classes.add("hentry");
        publicTerms.forEach((taxonomy, terms) -> {
            String prefix = switch (taxonomy) {
                case "category" -> "category";
                case "post_tag" -> "tag";
                default -> taxonomy;
            };
            for (TermSnapshot term : terms) {
                String slug = term.slug();
                String name = slug.isEmpty() || phpNumeric(slug)
                        ? Long.toString(term.id().value())
                        : slug;
                classes.add(prefix + "-" + name);
            }
        });
        return List.copyOf(classes);
    }

    /**
     * Whether PHP's {@code is_numeric} holds for a slug: digits, or digits with one {@code e} exponent that may carry
     * a sign (the other numeric forms hold characters a slug never has).
     */
    static boolean phpNumeric(String slug) {
        int i = 0;
        int length = slug.length();
        int digits = 0;
        while (i < length && isDigit(slug.charAt(i))) {
            i++;
            digits++;
        }
        if (digits == 0) {
            return false;
        }
        if (i == length) {
            return true;
        }
        if (slug.charAt(i) != 'e') {
            return false;
        }
        i++;
        if (i < length && (slug.charAt(i) == '-' || slug.charAt(i) == '+')) {
            i++;
        }
        int exponent = 0;
        while (i < length && isDigit(slug.charAt(i))) {
            i++;
            exponent++;
        }
        return exponent > 0 && i == length;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String gmt(Instant instant) {
        return DATE.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * The plain permalink: {@code ?p=<id>} for posts, {@code ?page_id=<id>} for pages, {@code ?attachment_id=<id>}
     * for attachments, and for another type
     * {@code ?<type>=<slug>} when the type is public and the post is neither a draft, pending, scheduled nor an
     * auto-draft and has a slug, else {@code ?post_type=<type>&p=<id>}.
     */
    String link(PostTypeDefinition type, PostSnapshot post) {
        String home = urls.home();
        return switch (type.key()) {
            case "post" -> home + "?p=" + post.id().value();
            case "page" -> home + "?page_id=" + post.id().value();
            case "attachment" -> home + "?attachment_id=" + post.id().value();
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
