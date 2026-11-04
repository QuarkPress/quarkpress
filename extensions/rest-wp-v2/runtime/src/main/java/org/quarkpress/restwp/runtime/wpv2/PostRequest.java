package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.lib.wpblocks.BlockParseException;
import org.quarkpress.lib.wpblocks.WpBlocks;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPasswords;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.security.UserProfiles;

/**
 * A request turned into post data, as WordPress's posts controller prepares it before writing anything, in its
 * order: an {@code id} sent to a create (one empty in PHP's sense) names no post; title, content and excerpt (a
 * string, or an object's {@code raw}; an object's empty title is ignored), the
 * status and its capability, the dates, the slug, the author, the password and its conflicts with stickiness, the
 * parent, the menu order, the comment and ping statuses, and the content's block delimiters written again by the
 * block serializer for posts and pages. A draft or pending post given a slug gets it made unique as if it were
 * published. On an update, fields the request does not name are left as stored, and a {@code date} or
 * {@code date_gmt} sent as null clears both dates, which the writer resolves. The first error is thrown as a
 * {@link RestException}; nothing is written.
 *
 * @param changes the post fields for the posts module
 * @param sticky whether the post is to be sticky, when the type is {@code post} and the request says
 * @param featuredMedia the requested featured image, when the type has one and the request names it
 * @param format the requested format, when the type has formats and the request names one
 * @param template the requested template, when the request has one
 * @param terms the requested term ids by taxonomy key, for the type's REST taxonomies the request names
 * @param meta the requested registered meta, when the request has it
 * @param datesCleared whether an update clears the post's dates (a null {@code date} or {@code date_gmt}): the post is
 *     then dated now, its date floating when the status it gets is draft, pending or auto-draft
 */
record PostRequest(
        PostChanges changes,
        Optional<Boolean> sticky,
        OptionalLong featuredMedia,
        Optional<String> format,
        Optional<String> template,
        Map<String, List<Long>> terms,
        Optional<JsonNode> meta,
        boolean datesCleared) {

    /** The types whose content WordPress runs through its block hooks, which write every block delimiter again. */
    private static final Set<String> BLOCK_HOOK_TYPES = Set.of("post", "page", "wp_block", "wp_navigation");

    PostRequest {
        terms = Collections.unmodifiableMap(new LinkedHashMap<>(terms));
    }

    /** The beans the preparation reads. */
    record Services(
            PostPermissions permissions,
            PostStatuses statuses,
            Posts posts,
            PostRepository rows,
            UserProfiles profiles,
            SiteContext site) {}

    /**
     * Prepares the request for the post's type, for a create when {@code stored} is empty, else for an update of the
     * stored post.
     */
    static PostRequest prepare(
            PostRoutes routes, RestRequest request, Optional<PostSnapshot> stored, Services services) {
        PostChanges.Builder changes = PostChanges.builder();
        if (stored.isEmpty()) {
            if (request.param("id").isPresent()) {
                // an id the create check let through (empty in PHP's sense) is looked up, and names no post
                throw new RestException(RestError.of("rest_post_invalid_id", "Invalid post ID.", 404));
            }
            changes.type(routes.key());
        }
        if (routes.has("title")) {
            request.param("title").ifPresent(value -> {
                if (value.isObject()) {
                    // an object's title counts only when it is not empty
                    raw(value).filter(raw -> !raw.isEmpty()).ifPresent(changes::title);
                } else if (!value.isContainerNode()) {
                    changes.title(PhpValues.toPhpString(value));
                }
            });
        }
        Optional<String> content = routes.has("content") ? text(request, "content") : Optional.empty();
        content.ifPresent(changes::content);
        if (routes.has("excerpt")) {
            text(request, "excerpt").ifPresent(changes::excerpt);
        }
        Optional<String> requestedStatus =
                request.param("status").filter(JsonNode::isTextual).map(JsonNode::textValue);
        if (requestedStatus.isPresent()
                && (stored.isEmpty() || !stored.get().status().equals(requestedStatus.get()))) {
            changes.status(status(routes, request, requestedStatus.get(), services));
        }
        boolean datesCleared = dates(request, stored, services.site().zone(), changes);
        Optional<String> slug = request.param("slug").map(PhpValues::toPhpString);
        slug.ifPresent(changes::slug);
        if (routes.has("author")) {
            author(request, services).ifPresent(changes::author);
        }
        boolean stickyRequested = routes.key().equals("post")
                && request.param("sticky").map(PhpValues::toBoolean).orElse(false);
        Optional<String> password = request.param("password").map(PhpValues::toPhpString);
        if (password.isPresent()) {
            if (!password.get().isEmpty()) {
                if (stickyRequested) {
                    throw invalidField("A post can not be sticky and have a password.");
                }
                if (stored.map(PostSnapshot::sticky).orElse(false)) {
                    throw invalidField("A sticky post can not be password protected.");
                }
            }
            changes.password(password.get());
        }
        if (stickyRequested && stored.map(PostPasswords::required).orElse(false)) {
            // the stored password counts, even when this request clears it
            throw invalidField("A password protected post can not be set to sticky.");
        }
        OptionalLong parent = OptionalLong.empty();
        if (routes.type().hierarchical() && request.param("parent").isPresent()) {
            parent = OptionalLong.of(parent(request.param("parent").get(), services));
            changes.parent(parent.getAsLong());
        }
        if (routes.has("menu_order")) {
            request.param("menu_order").map(PhpValues::toLong).ifPresent(order -> changes.menuOrder(order.intValue()));
        }
        if (routes.has("comment_status")) {
            nonEmptyText(request, "comment_status").ifPresent(changes::commentStatus);
        }
        if (routes.has("ping_status")) {
            nonEmptyText(request, "ping_status").ifPresent(changes::pingStatus);
        }
        if (BLOCK_HOOK_TYPES.contains(routes.key())) {
            content.filter(text -> !text.isEmpty()).ifPresent(text -> changes.content(reserialized(text)));
        }
        // a draft or pending post given a slug takes the slug a published post would get
        Optional<String> effectiveStatus = requestedStatus.or(() -> stored.map(PostSnapshot::status));
        if (slug.isPresent()
                && !slug.get().isEmpty()
                && effectiveStatus
                        .filter(s -> s.equals("draft") || s.equals("pending"))
                        .isPresent()) {
            Optional<PostId> under = parent.isPresent() && parent.getAsLong() > 0
                    ? Optional.of(PostId.of(parent.getAsLong()))
                    : Optional.empty();
            changes.slug(services.posts()
                    .uniqueSlug(
                            stored.map(PostSnapshot::type).orElse(routes.key()),
                            slug.get(),
                            stored.map(PostSnapshot::id),
                            under,
                            "publish"));
        }
        return new PostRequest(
                changes.build(),
                routes.key().equals("post") ? request.param("sticky").map(PhpValues::toBoolean) : Optional.empty(),
                routes.has("featured_media")
                        ? request.param("featured_media")
                                .map(PhpValues::toLong)
                                .map(OptionalLong::of)
                                .orElse(OptionalLong.empty())
                        : OptionalLong.empty(),
                routes.has("format") ? nonEmptyText(request, "format") : Optional.empty(),
                routes.has("template") ? template(request, stored) : Optional.empty(),
                terms(routes, request),
                routes.has("meta") ? request.param("meta") : Optional.empty(),
                datesCleared);
    }

    /**
     * WordPress's status rule: draft and pending are free; private needs the type's {@code publish_posts}, and so do
     * publish and future; a status that is not registered becomes draft.
     */
    private static String status(PostRoutes routes, RestRequest request, String status, Services services) {
        switch (status) {
            case "draft", "pending" -> {
                return status;
            }
            case "private" -> {
                if (!services.permissions().canType(request.identity(), "publish_posts", routes.key())) {
                    throw new RestException(RestError.of(
                            "rest_cannot_publish",
                            "Sorry, you are not allowed to create private posts in this post type.",
                            request.authCode()));
                }
                return status;
            }
            case "publish", "future" -> {
                if (!services.permissions().canType(request.identity(), "publish_posts", routes.key())) {
                    throw new RestException(RestError.of(
                            "rest_cannot_publish",
                            "Sorry, you are not allowed to publish posts in this post type.",
                            request.authCode()));
                }
                return status;
            }
            default -> {
                return services.statuses().find(status).isPresent() ? status : "draft";
            }
        }
    }

    /**
     * WordPress's dates: a non-empty {@code date} wins over {@code date_gmt}. A date without offset is a wall time of
     * the site's zone, one with an offset an instant; a GMT date without offset is read as UTC. Fractions are
     * dropped and fields past their range roll over. The pair is applied only when the local date differs from the
     * stored one. On an update, a null {@code date} or {@code date_gmt} clears both instead: true then, and nothing is
     * given (a create without dates is dated as if none were sent).
     */
    private static boolean dates(
            RestRequest request, Optional<PostSnapshot> stored, ZoneId zone, PostChanges.Builder changes) {
        if (stored.isPresent() && (sentNull(request, "date") || sentNull(request, "date_gmt"))) {
            return true;
        }
        Optional<String> date = nonEmptyText(request, "date");
        Optional<String> dateGmt = nonEmptyText(request, "date_gmt");
        LocalDateTime local;
        Instant gmt;
        if (date.isPresent()) {
            local = PostCollection.localTime(date.get(), zone);
            gmt = hasOffset(date.get())
                    ? PostCollection.localTime(date.get(), ZoneOffset.UTC).toInstant(ZoneOffset.UTC)
                    : local.atZone(zone).toInstant();
        } else if (dateGmt.isPresent()) {
            gmt = PostCollection.localTime(dateGmt.get(), ZoneOffset.UTC).toInstant(ZoneOffset.UTC);
            local = LocalDateTime.ofInstant(gmt, zone);
        } else {
            return false;
        }
        if (stored.isPresent() && stored.get().localDate(zone).equals(local)) {
            return false;
        }
        changes.date(local).dateGmt(gmt);
        if (stored.isPresent()) {
            changes.editDate(true);
        }
        return false;
    }

    /** Whether the request sends the parameter as null alone (a JSON null, which reads as absent). */
    private static boolean sentNull(RestRequest request, String name) {
        return request.param(name).isEmpty() && request.paramNames().contains(name);
    }

    /** Whether a date-time that passed the schema's check ends with {@code Z} or an offset. */
    static boolean hasOffset(String text) {
        // the date and time take the first 19 characters, then an optional fraction
        int at = 19;
        if (at < text.length() && text.charAt(at) == '.') {
            at++;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
        }
        return at < text.length() && "Z+-".indexOf(text.charAt(at)) >= 0;
    }

    /**
     * A non-empty author other than the caller must be a user of the network, a member of the site or not; 0 is no
     * author given.
     */
    private static Optional<Long> author(RestRequest request, Services services) {
        Optional<JsonNode> value = request.param("author").filter(given -> !PhpValues.isEmpty(given));
        if (value.isEmpty()) {
            return Optional.empty();
        }
        long id = PhpValues.toLong(value.get());
        boolean caller = request.user().map(user -> user.value() == id).orElse(false);
        if (!caller && (id <= 0 || services.profiles().find(UserId.of(id)).isEmpty())) {
            throw new RestException(RestError.of("rest_invalid_author", "Invalid author ID.", 400));
        }
        return Optional.of(id);
    }

    /** A parent: 0 for none, else any post of the site, of any type. */
    private static long parent(JsonNode value, Services services) {
        long id = PhpValues.toLong(value);
        if (id == 0) {
            return 0;
        }
        if (id < 0
                || services.rows()
                        .findPost(services.site().siteId(), PostId.of(id))
                        .isEmpty()) {
            throw new RestException(RestError.of("rest_post_invalid_id", "Invalid post parent ID.", 400));
        }
        return id;
    }

    /** The template to store: on a create, one no theme offers becomes {@code ""}, so only that is left. */
    private static Optional<String> template(RestRequest request, Optional<PostSnapshot> stored) {
        Optional<String> template = request.param("template").map(PhpValues::toPhpString);
        if (stored.isEmpty()) {
            return template.map(value -> "");
        }
        return template;
    }

    /** The requested ids of each REST taxonomy of the type whose base the request names, positive ones once each. */
    private static Map<String, List<Long>> terms(PostRoutes routes, RestRequest request) {
        Map<String, List<Long>> terms = new LinkedHashMap<>();
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            Optional<JsonNode> value = request.param(PostRoutes.base(taxonomy));
            if (value.isEmpty()) {
                continue;
            }
            // each id once, in the order given: the stored assignment is a set
            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode item : PhpValues.values(value.get())) {
                long id = PhpValues.toLong(item);
                if (id > 0) {
                    ids.add(id);
                }
            }
            terms.put(taxonomy.key(), List.copyOf(ids));
        }
        return terms;
    }

    /** The block markup written again as WordPress's serializer writes every delimiter; as it is if unparseable. */
    private static String reserialized(String content) {
        try {
            return WpBlocks.serializeCanonical(WpBlocks.parse(content));
        } catch (BlockParseException beyondLimits) {
            return content;
        }
    }

    /** A string, or an object's {@code raw} (empty included). */
    private static Optional<String> text(RestRequest request, String name) {
        Optional<JsonNode> value = request.param(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.get().isObject()) {
            return raw(value.get());
        }
        return value.get().isContainerNode() ? Optional.empty() : Optional.of(PhpValues.toPhpString(value.get()));
    }

    private static Optional<String> raw(JsonNode object) {
        JsonNode raw = object.get("raw");
        return raw == null || raw.isNull() || raw.isContainerNode()
                ? Optional.empty()
                : Optional.of(PhpValues.toPhpString(raw));
    }

    private static Optional<String> nonEmptyText(RestRequest request, String name) {
        return request.param(name).filter(value -> !PhpValues.isEmpty(value)).map(PhpValues::toPhpString);
    }

    private static RestException invalidField(String message) {
        return new RestException(RestError.of("rest_invalid_field", message, 400));
    }
}
