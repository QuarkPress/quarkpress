package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TitleFilter;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.PreviewSessions;
import org.quarkpress.posts.PreviewTokens;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.taxonomy.PostFormats;

/**
 * The minimal front-end page a site serves until themes exist: for a site-root request naming a post ({@code p},
 * {@code page_id}, {@code attachment_id}, {@code post_type} with {@code p}) or asking for a preview, the site's name,
 * the post's title, its date and its content through the registered filters, with WordPress's rules for who sees
 * what. Only requests the {@link FrontEndFilter} re-routed here are served; a request that reaches the path any other
 * way is answered 404 with an empty body.
 *
 * <p>The viewer is the user of the {@code quarkpress_preview} session cookie, which this resource alone reads (no
 * REST route ever does): of several such cookies, the first whose session this site holds counts. For the render,
 * the request's identity is replaced by one carrying that user's principal, and put back afterwards; without a
 * session, or with an expired one, the viewer is anonymous whatever else the request carried. In order: a request
 * with both {@code preview_id} and {@code preview_nonce} whose token is not the viewer's valid token of that post
 * answers 403 "Sorry, you are not allowed to preview drafts." (an HTML page, or WordPress's JSON {@code wp_die}
 * object when {@code Accept} names {@code application/json}); a {@code post_type} that names no publicly queryable
 * type is ignored, as WordPress ignores it, so {@code p} then names a post; a post that does not exist, or whose type
 * is not the one the parameter names, answers 404, unless it is a published post of a public type, which is
 * redirected (301) to its plain link as WordPress's canonical redirect of a 404 does; the status gate answers 404 to
 * a viewer who may not see the post (a published post to everyone; a draft, pending or scheduled post to a viewer
 * with {@code edit_post}; a private one with {@code read_post}; an auto-draft, a revision or a trashed post to
 * nobody); a published post asked for with {@code preview} and no valid pair is redirected (301) to its plain link,
 * as WordPress's canonical redirect does; a shown post that is not public is in preview mode, and a draft or a
 * pending post is dated now (a scheduled or private post keeps its date); in preview mode, for a viewer with
 * {@code edit_post} and a valid pair, the latest autosave of any user replaces the title, content and excerpt,
 * {@code post_format} replaces the format and {@code _thumbnail_id} (when {@code preview_id} is the post's id) the
 * featured image; a password-protected post shows its title and WordPress's notice instead of its content unless
 * the viewer is in preview mode with {@code edit_post}. Previews are sent with no-cache headers.
 */
@Path(PreviewPage.PATH)
@Blocking
public class PreviewPage {

    /** The internal path front-end page requests are re-routed to. */
    public static final String PATH = "/_quarkpress/front-end";

    /** The session cookie's name. */
    public static final String COOKIE = "quarkpress_preview";

    static final String CANNOT_PREVIEW = "Sorry, you are not allowed to preview drafts.";

    static final String PASSWORD_NOTICE =
            "This content is password protected. To view it please enter your password below:";

    static final String HTML_TYPE = "text/html; charset=UTF-8";

    private static final String NOT_FOUND = "Page not found";

    /** The most session cookies of a request that are looked up. */
    private static final int MAX_COOKIES = 10;

    private static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";

    private static final String NO_CACHE = "no-cache, must-revalidate, max-age=0";

    /** WordPress's no-cache value on a page nobody may see. */
    private static final String NO_STORE = NO_CACHE + ", no-store, private";

    private static final DateTimeFormatter MACHINE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    /** The identity of a request viewed by nobody. */
    private static final SecurityIdentity ANONYMOUS = QuarkusSecurityIdentity.builder()
            .setAnonymous(true)
            .setPrincipal(new QuarkusPrincipal(""))
            .build();

    /** A rendered page: its status, its HTML (empty for a redirect) and its headers besides the content type. */
    public record PageAnswer(int status, String html, Map<String, String> headers) {}

    private record Rendering(SiteId site) implements RenderContext {}

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    PostPermissions permissions;

    @Inject
    PreviewSessions sessions;

    @Inject
    PreviewTokens tokens;

    @Inject
    PostFormats formats;

    @Inject
    UserProfiles profiles;

    @Inject
    CurrentIdentityAssociation association;

    @Inject
    QuarkPressClock clock;

    @Inject
    RestUrls urls;

    /** The autosave overlay; a bean only while the revisions module is present. */
    @Inject
    Instance<AutosaveOverlay> autosaves;

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

    @GET
    @Path("page")
    public Response get(@Context RoutingContext context) {
        return serve(context, false);
    }

    @HEAD
    @Path("page")
    public Response head(@Context RoutingContext context) {
        return serve(context, true);
    }

    private Response serve(RoutingContext context, boolean head) {
        if (!(context.get(FrontEndFilter.ENTRY) instanceof FrontEndFilter.Entry entry) || !site.isResolved()) {
            return Response.status(404).build();
        }
        Optional<UserId> viewer = viewer(context);
        SecurityIdentity previous = association.getIdentity();
        association.setIdentity(viewer.flatMap(this::identity).orElse(ANONYMOUS));
        try {
            return response(answer(entry, viewer, context.request().getHeader("Accept")), head);
        } finally {
            association.setIdentity(previous);
        }
    }

    /**
     * The user of the first session cookie whose session this site holds: a browser sends every cookie of the name
     * whose path covers the request (a parent site's, a stale one), in an order the page does not rely on.
     */
    private Optional<UserId> viewer(RoutingContext context) {
        for (String value : sessionCookies(context.request().headers().getAll("Cookie"))) {
            Optional<UserId> user = sessions.user(value);
            if (user.isPresent()) {
                return user;
            }
        }
        return Optional.empty();
    }

    /**
     * The values of the {@code quarkpress_preview} pairs of the {@code Cookie} headers, in the order sent, at most
     * {@value #MAX_COOKIES} (the server's header limit bounds the text). A value in double quotes loses them.
     */
    static List<String> sessionCookies(List<String> headers) {
        List<String> values = new ArrayList<>();
        for (String header : headers) {
            if (values.size() == MAX_COOKIES) {
                break;
            }
            int start = 0;
            while (start <= header.length() && values.size() < MAX_COOKIES) {
                int end = header.indexOf(';', start);
                if (end < 0) {
                    end = header.length();
                }
                String pair = header.substring(start, end).strip();
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).strip().equals(COOKIE)) {
                    String value = pair.substring(equals + 1).strip();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
                start = end + 1;
            }
        }
        return values;
    }

    /** The identity carrying {@code user}'s principal, the shape the security module's tokens leave. */
    private Optional<SecurityIdentity> identity(UserId user) {
        return profiles.find(user)
                .map(profile -> QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(profile.subject()))
                        .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, profile.subject()))
                        .build());
    }

    /** The answer, decided in WordPress's order: the pair check, the post lookup, then the page. */
    private PageAnswer answer(FrontEndFilter.Entry entry, Optional<UserId> viewer, String accept) {
        Map<String, String> query = flatten(entry.query());
        boolean pair = query.containsKey("preview_id") && query.containsKey("preview_nonce");
        boolean armed = false;
        if (pair) {
            long id = PhpValues.toLong(entry.query().get("preview_id"));
            armed = viewer.isPresent()
                    && id > 0
                    && tokens.verify(PostId.of(id), query.get("preview_nonce"), viewer.get());
            if (!armed) {
                return died(403, CANNOT_PREVIEW, acceptsJson(accept));
            }
        }
        Optional<PostSnapshot> post = requested(entry.query());
        if (post.isEmpty()) {
            return misnamed(entry.query()).map(this::notFoundRedirect).orElseGet(this::notFound);
        }
        return render(post.get(), viewer, query, armed);
    }

    /**
     * The page for {@code post} as {@code viewer} sees it, for a request whose query is {@code query}: the status
     * gate, the canonical redirect, then the page with its overlay and password rule.
     */
    public PageAnswer render(PostSnapshot post, Optional<UserId> viewer, Map<String, String> query) {
        return render(post, viewer, query, false);
    }

    private PageAnswer render(PostSnapshot post, Optional<UserId> viewer, Map<String, String> query, boolean armed) {
        SecurityIdentity identity = association.getIdentity();
        boolean canEdit = permissions.can(identity, "edit_post", post);
        Optional<PostStatusDefinition> status = statuses.find(post.status());
        boolean publicly = status.map(PostStatusDefinition::publicly).orElse(false);
        boolean shown;
        if (status.isEmpty()) {
            shown = canEdit;
        } else if (publicly) {
            shown = true;
        } else if (status.get().protectedStatus()) {
            shown = canEdit;
        } else if (status.get().privateStatus()) {
            shown = permissions.can(identity, "read_post", post);
        } else {
            shown = false;
        }
        if (!shown) {
            return notFound();
        }
        boolean previewAsked = query.containsKey("preview") && !phpEmpty(query.get("preview"));
        if (publicly && previewAsked && !armed) {
            return redirect(post);
        }
        boolean previewMode = previewAsked || !publicly;
        boolean overlay = previewMode && canEdit && armed;
        String title = post.title();
        String content = post.content();
        if (overlay) {
            Optional<AutosaveOverlay.Autosave> autosave = latestAutosave(post.id());
            if (autosave.isPresent()) {
                title = autosave.get().title();
                content = autosave.get().content();
            }
        }
        String format = formats.of(post.id());
        long thumbnail = FeaturedImages.of(post);
        if (overlay) {
            String asked = query.get("post_format");
            if (asked != null) {
                if (asked.equals("standard")) {
                    format = "standard";
                } else if (formats.exists(asked)) {
                    format = asked;
                }
            }
            String thumbnailAsked = query.get("_thumbnail_id");
            if (thumbnailAsked != null
                    && toLong(query.get("preview_id")) == post.id().value()) {
                long id = toLong(thumbnailAsked);
                thumbnail = id > 0 ? id : 0;
            }
        }
        boolean lifted = previewMode && canEdit;
        boolean locked = PostPasswords.required(post) && !lifted;
        ZoneId zone = site.zone();
        // a draft or a pending post is shown as of now; a scheduled or private post keeps its own date
        boolean datedNow = status.map(PostStatusDefinition::protectedStatus).orElse(false)
                && !post.status().equals("future");
        Instant shownAt = datedNow ? clock.now() : post.publishedAt().orElseGet(clock::now);
        Map<String, String> headers = new LinkedHashMap<>();
        if (previewMode) {
            headers.put("Expires", EXPIRES);
            headers.put("Cache-Control", NO_CACHE);
        }
        Rendering rendering = new Rendering(post.site());
        String renderedTitle = Filters.apply(titleFilters, title, (filter, value) -> filter.apply(value, rendering));
        String body = locked
                ? "<p class=\"post-password-notice\">" + PASSWORD_NOTICE + "</p>"
                : Filters.apply(contentFilters, content, (filter, value) -> filter.apply(value, rendering));
        StringBuilder html = new StringBuilder();
        head(html, renderedTitle);
        html.append("<body class=\"single single-")
                .append(post.type())
                .append(" postid-")
                .append(post.id().value())
                .append(" single-format-")
                .append(format)
                .append("\">\n");
        html.append("<header class=\"site-header\"><p class=\"site-title\">")
                .append(siteName())
                .append("</p></header>\n");
        html.append("<main>\n<article id=\"post-").append(post.id().value()).append("\">\n");
        html.append("<h1 class=\"entry-title\">").append(renderedTitle).append("</h1>\n");
        ZonedDateTime date = shownAt.atZone(zone);
        html.append("<p class=\"entry-date\"><time datetime=\"")
                .append(MACHINE_DATE.format(date))
                .append("\">")
                .append(HUMAN_DATE.format(date))
                .append("</time></p>\n");
        if (thumbnail > 0) {
            html.append("<figure class=\"post-thumbnail\" data-attachment-id=\"")
                    .append(thumbnail)
                    .append("\"></figure>\n");
        }
        html.append("<div class=\"entry-content\">").append(body).append("</div>\n");
        html.append("</article>\n</main>\n</body>\n</html>\n");
        return new PageAnswer(200, html.toString(), headers);
    }

    /** The latest autosave of any user, when the revisions module is present. */
    private Optional<AutosaveOverlay.Autosave> latestAutosave(PostId post) {
        if (!autosaves.isResolvable()) {
            return Optional.empty();
        }
        return autosaves.get().latest(post);
    }

    /**
     * The post the query names, when it exists on this site with the type the parameter stands for:
     * {@code attachment_id} an attachment, {@code page_id} a page, {@code p} a post or the {@code post_type} given
     * when that type is publicly queryable (any other {@code post_type} is ignored, so {@code p} names a post).
     */
    private Optional<PostSnapshot> requested(ObjectNode query) {
        String type;
        JsonNode id;
        if (query.has("attachment_id")) {
            type = "attachment";
            id = query.get("attachment_id");
        } else if (query.has("page_id")) {
            type = "page";
            id = query.get("page_id");
        } else if (query.has("p")) {
            // a post_type naming no publicly queryable type is dropped, as WordPress drops the query var
            JsonNode postType = query.get("post_type");
            type = postType != null && postType.isTextual() && queryable(postType.textValue())
                    ? postType.textValue()
                    : "post";
            id = query.get("p");
        } else {
            return Optional.empty();
        }
        long value = PhpValues.toLong(id);
        if (value <= 0 || !types.registered(type)) {
            return Optional.empty();
        }
        return posts.findPost(site.siteId(), PostId.of(value))
                .map(PostEntity::toSnapshot)
                .filter(post -> post.type().equals(type));
    }

    /** WordPress's canonical redirect (301) to the post's plain link. */
    private PageAnswer redirect(PostSnapshot post) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Location", urls.home() + plainLink(post));
        return new PageAnswer(301, "", headers);
    }

    /** The canonical redirect of a 404, which keeps the 404's no-cache headers as WordPress's does. */
    private PageAnswer notFoundRedirect(PostSnapshot post) {
        PageAnswer redirect = redirect(post);
        redirect.headers().put("Expires", EXPIRES);
        redirect.headers().put("Cache-Control", NO_STORE);
        return redirect;
    }

    /**
     * For a query whose post was not found: the published post of a public type that its id names under another
     * type, which WordPress's canonical redirect of a 404 sends the visitor to ({@code ?p=} naming a page, say).
     */
    private Optional<PostSnapshot> misnamed(ObjectNode query) {
        JsonNode id = query.has("attachment_id")
                ? query.get("attachment_id")
                : query.has("page_id") ? query.get("page_id") : query.get("p");
        long value = id == null ? 0 : PhpValues.toLong(id);
        if (value <= 0) {
            return Optional.empty();
        }
        return posts.findPost(site.siteId(), PostId.of(value))
                .map(PostEntity::toSnapshot)
                .filter(post -> types.find(post.type())
                        .map(PostTypeDefinition::publicly)
                        .orElse(false))
                .filter(post -> statuses.find(post.status())
                        .map(PostStatusDefinition::publicly)
                        .orElse(false));
    }

    /** Whether the key names a registered post type that is publicly queryable. */
    private boolean queryable(String key) {
        return types.find(key).map(PostTypeDefinition::publiclyQueryable).orElse(false);
    }

    /** The post's plain front-end link below the site's home. */
    private static String plainLink(PostSnapshot post) {
        return switch (post.type()) {
            case "post" -> "?p=" + post.id().value();
            case "page" -> "?page_id=" + post.id().value();
            case "attachment" -> "?attachment_id=" + post.id().value();
            default -> "?" + post.type() + "=" + post.slug();
        };
    }

    private PageAnswer notFound() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Expires", EXPIRES);
        headers.put("Cache-Control", NO_STORE);
        StringBuilder html = new StringBuilder();
        head(html, NOT_FOUND);
        html.append("<body class=\"error404\">\n<header class=\"site-header\"><p class=\"site-title\">")
                .append(siteName())
                .append("</p></header>\n<main>\n<h1 class=\"entry-title\">")
                .append(NOT_FOUND)
                .append("</h1>\n</main>\n</body>\n</html>\n");
        return new PageAnswer(404, html.toString(), headers);
    }

    /** WordPress's {@code wp_die} answer: its JSON error object for a JSON request, else its error page. */
    private static PageAnswer died(int status, String message, boolean json) {
        ServedResponse died = SiteGate.died(status, "wp_die", message, json);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : died.headers().entrySet()) {
            headers.put(header.getKey(), header.getValue().get(0));
        }
        return new PageAnswer(status, new String(died.body(), StandardCharsets.UTF_8), headers);
    }

    /** The document's head: the title (the site's name alone when the post's title is empty) and the charset. */
    private void head(StringBuilder html, String title) {
        html.append("<!DOCTYPE html>\n<html lang=\"en-US\">\n<head>\n<meta charset=\"UTF-8\">\n<title>");
        String plain = withoutTags(title);
        if (!plain.isEmpty()) {
            html.append(plain).append(" &#8211; ");
        }
        html.append(siteName()).append("</title>\n</head>\n");
    }

    private String siteName() {
        return site.options().string("blogname", "");
    }

    /** The text with every {@code <...>} dropped, as a document title holds no markup. */
    static String withoutTags(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /** The query's parameters as text: a value node's text, an empty text for a list or object. */
    private static Map<String, String> flatten(ObjectNode query) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = query.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            flat.put(
                    field.getKey(),
                    field.getValue().isValueNode() ? field.getValue().asText() : "");
        }
        return flat;
    }

    /** PHP's {@code (int)} of a query value's text. */
    private static long toLong(String value) {
        return value == null ? 0 : PhpValues.toLong(TextNode.valueOf(value));
    }

    /** PHP's {@code empty()} of a query value: nothing, an empty text or {@code "0"}. */
    private static boolean phpEmpty(String value) {
        return value == null || value.isEmpty() || value.equals("0");
    }

    /** Whether the request's {@code Accept} names a JSON media type, as WordPress's {@code wp_die} tells. */
    private static boolean acceptsJson(String accept) {
        return JsonRequests.isJson(Optional.ofNullable(accept), Optional.empty());
    }

    private static Response response(PageAnswer answer, boolean head) {
        Response.ResponseBuilder builder = Response.status(answer.status());
        String type = HTML_TYPE;
        for (Map.Entry<String, String> header : answer.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Type")) {
                type = header.getValue();
            } else {
                builder.header(header.getKey(), header.getValue());
            }
        }
        if (head || answer.html().isEmpty()) {
            return builder.type(type).build();
        }
        return builder.entity(answer.html().getBytes(StandardCharsets.UTF_8))
                .type(type)
                .build();
    }
}
