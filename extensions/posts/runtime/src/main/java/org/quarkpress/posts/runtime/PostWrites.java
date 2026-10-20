package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.ContentSanitizer;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDraft;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.PostWriteContext;
import org.quarkpress.posts.PostWriteFilter;
import org.quarkpress.posts.SaveFilters;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;
import org.quarkpress.security.UserProfiles;

/**
 * WordPress's post save, without capability checks (the services check before they call it): the caller holds the
 * site's post lock and, for an update, the post row's lock.
 */
@ApplicationScoped
public class PostWrites {

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    SlugSanitizer slugSanitizer;

    @Inject
    ContentSanitizer contentSanitizer;

    @Inject
    PostSlugs slugs;

    @Inject
    @All
    List<InstanceHandle<PostWriteFilter>> writeFilters;

    @Inject
    SiteContext site;

    @Inject
    QuarkPressClock clock;

    @Inject
    Acting acting;

    @Inject
    PostEvents events;

    @Inject
    UserProfiles profiles;

    @Inject
    PostLocks locks;

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    PostCapabilityFacts facts;

    /** Inserts a post: WordPress's {@code wp_insert_post} for a new post. */
    PostSnapshot insert(SiteId siteId, PostChanges changes) {
        Instant now = PostDates.now(clock);
        ZoneId zone = site.zone();
        SaveFilters filters = acting.saveFilters(siteId);
        String type = PostRules.type(changes.type().orElse(""));
        String title = contentSanitizer.title(changes.title().orElse(""), filters);
        String content = contentSanitizer.content(changes.content().orElse(""), filters);
        String excerpt = contentSanitizer.excerpt(changes.excerpt().orElse(""), filters);
        if (PostRules.maybeEmpty(type, title, content, excerpt, types)) {
            throw PostException.emptyContent();
        }
        String status = PostRules.status(changes.status().orElse(""), type);
        Optional<UserId> author =
                changes.author().isPresent() ? author(changes.author().getAsLong()) : acting.user();
        Optional<PostId> parent = changes.parent().isPresent()
                ? parent(siteId, changes.parent().getAsLong(), Optional.empty())
                : Optional.empty();
        String candidate = changes.slug().orElse("");
        // a pending post of a registered type loses its slug when the caller cannot publish such posts
        if (status.equals("pending")
                && types.capabilities(type)
                        .map(names -> !acting.can(siteId, names.publishPosts(), Optional.empty()))
                        .orElse(false)) {
            candidate = "";
        }
        String slug = slugFrom(siteId, candidate, title, status, type, parent, Optional.empty());
        PostDates.Resolved dates =
                PostDates.resolve(changes.date(), changes.dateGmt(), statuses.floating(status), false, now, zone);
        status = PostDates.coerceStatus(status, dates.publishedAt(), now, type.equals("attachment"));
        String commentStatus = PostRules.commentStatus(
                changes.commentStatus().orElse(""),
                false,
                PostRules.defaultCommentStatus(type, types, option("default_comment_status")));
        String pingStatus = PostRules.pingStatus(
                changes.pingStatus().orElse(""),
                PostRules.defaultPingStatus(type, types, option("default_ping_status")));
        String password = PostRules.password(changes.password().orElse(""), status);
        slug = slugs.unique(siteId, slug, Optional.empty(), Optional.empty(), status, type, parent);
        PostDraft draft = new PostDraft(
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                author,
                parent,
                changes.menuOrder().orElse(0),
                dates.publishedAt(),
                dates.modifiedAt(),
                commentStatus,
                pingStatus,
                password);
        draft = filtered(siteId, draft, new PostWriteContext(siteId, Optional.empty(), acting.user(), false), false);
        PostEntity row = PostEntity.create(siteId);
        apply(row, draft);
        row.createdAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        return written(() -> {
            posts.persist(row);
            posts.flush();
            row.guid = PostRules.guid(home(), row.type, row.id);
            lateSlug(siteId, row);
            posts.flush();
            PostSnapshot saved = row.toSnapshot();
            events.saved(siteId, now, "new", Optional.empty(), saved, false);
            return saved;
        });
    }

    /**
     * Updates a post: WordPress's {@code wp_update_post}. The caller holds the site's post lock and the row lock.
     * Fields not given keep their stored values; every text field is filtered again with the caller's settings.
     */
    PostSnapshot update(SiteId siteId, PostEntity row, PostChanges changes) {
        Instant now = PostDates.now(clock);
        ZoneId zone = site.zone();
        PostSnapshot before = row.toSnapshot();
        SaveFilters filters = acting.saveFilters(siteId);
        String type = PostRules.type(changes.type().orElse(before.type()));
        String title = contentSanitizer.title(changes.title().orElse(before.title()), filters);
        String content = contentSanitizer.content(changes.content().orElse(before.content()), filters);
        String excerpt = contentSanitizer.excerpt(changes.excerpt().orElse(before.excerpt()), filters);
        if (PostRules.maybeEmpty(type, title, content, excerpt, types)) {
            throw PostException.emptyContent();
        }
        String status = PostRules.status(changes.status().orElse(before.status()), type);
        Optional<UserId> author =
                changes.author().isPresent() ? author(changes.author().getAsLong()) : before.author();
        Optional<PostId> parent = changes.parent().isPresent()
                ? parent(siteId, changes.parent().getAsLong(), Optional.of(before.id()))
                : before.parent();
        String candidate = changes.slug().orElse(before.slug());
        // a pending post loses its slug when the caller cannot publish this post
        if (status.equals("pending") && !acting.can(siteId, "publish_post", Optional.of(facts.of(before)))) {
            candidate = "";
        }
        String slug = updateSlug(siteId, candidate, before, title, status, type, parent);
        PostDates.Resolved dates = PostDates.resolve(
                givenOrKeptDate(changes, before, zone),
                givenOrKeptGmt(changes, before, zone),
                statuses.floating(status),
                true,
                now,
                zone);
        status = PostDates.coerceStatus(status, dates.publishedAt(), now, type.equals("attachment"));
        String commentStatus = PostRules.commentStatus(
                changes.commentStatus().orElse(before.commentStatus()),
                true,
                PostRules.defaultCommentStatus(type, types, option("default_comment_status")));
        String pingStatus = PostRules.pingStatus(
                changes.pingStatus().orElse(before.pingStatus()),
                PostRules.defaultPingStatus(type, types, option("default_ping_status")));
        String password = PostRules.password(changes.password().orElse(before.password()), status);
        // WordPress's loop check runs once the dates are valid, before the slug is made unique
        Hierarchy.Decision decision = Hierarchy.check(before.id(), parent, id -> storedParent(siteId, id));
        parent = decision.parent();
        boolean cut = false;
        try {
            for (PostId member : decision.cut()) {
                PostEntity memberRow =
                        posts.lockPost(siteId, member).orElseThrow(() -> PostException.invalidPost(member));
                update(siteId, memberRow, PostChanges.builder().parent(0).build());
                cut = true;
            }
            slug = slugs.unique(
                    siteId, slug, Optional.of(before.id()), Optional.of(before.slug()), status, type, parent);
            PostDraft draft = new PostDraft(
                    type,
                    status,
                    slug,
                    title,
                    content,
                    excerpt,
                    author,
                    parent,
                    changes.menuOrder().orElse(before.menuOrder()),
                    dates.publishedAt(),
                    dates.modifiedAt(),
                    commentStatus,
                    pingStatus,
                    password);
            PostDraft saved = filtered(
                    siteId, draft, new PostWriteContext(siteId, Optional.of(before), acting.user(), true), true);
            return written(() -> {
                apply(row, saved);
                lateSlug(siteId, row);
                trackOldSlugAndDate(row, before, zone);
                posts.flush();
                PostSnapshot after = row.toSnapshot();
                events.saved(siteId, now, before.status(), Optional.of(before), after, true);
                return after;
            });
        } catch (RuntimeException failure) {
            // a loop member re-parented above is a write: the failed update must not leave it for the caller to commit
            if (cut) {
                markRollbackOnly(failure);
            }
            throw failure;
        }
    }

    /**
     * The local date an update saves: none (now) for a post whose date floats unless {@code editDate} is set; else
     * the given one when a date is given, else the stored one.
     */
    private static Optional<LocalDateTime> givenOrKeptDate(PostChanges changes, PostSnapshot before, ZoneId zone) {
        if (floats(before) && !changes.editDate()) {
            return Optional.empty();
        }
        if (changes.date().isPresent() || changes.dateGmt().isPresent()) {
            return changes.date();
        }
        return Optional.of(before.localDate(zone));
    }

    /**
     * The GMT date an update saves: none for a post whose date floats unless {@code editDate} is set; else the given
     * one; else, for a local date given alone, that date converted with the site's zone when the post had a GMT date
     * (a post without one keeps floating while its status floats); else the stored one.
     */
    private static Optional<Instant> givenOrKeptGmt(PostChanges changes, PostSnapshot before, ZoneId zone) {
        if (floats(before) && !changes.editDate()) {
            return Optional.empty();
        }
        if (changes.dateGmt().isPresent()) {
            return changes.dateGmt();
        }
        if (changes.date().isPresent()) {
            return before.publishedAt().isPresent()
                    ? Optional.of(PostDates.toInstant(changes.date().get(), zone))
                    : Optional.empty();
        }
        return before.publishedAt();
    }

    /** Whether the stored post's date floats: a draft, pending or auto-draft post without a GMT date. */
    private static boolean floats(PostSnapshot post) {
        return PostRules.NO_SLUG_STATUSES.contains(post.status())
                && post.publishedAt().isEmpty();
    }

    /**
     * The slug of an update before uniqueness: an empty candidate as for an insert; a candidate whose lower-cased URL
     * encoding equals its old-format sanitizing, which is also the stored slug, keeps that form; anything else is
     * sanitized.
     */
    String updateSlug(
            SiteId siteId,
            String candidate,
            PostSnapshot before,
            String title,
            String status,
            String type,
            Optional<PostId> parent) {
        if (!PostRules.phpEmpty(candidate)) {
            String oldFormat = Slugs.sanitizeTitleForQuery(candidate);
            if (Slugs.phpUrlencode(candidate).toLowerCase(Locale.ROOT).equals(oldFormat)
                    && before.slug().equals(oldFormat)) {
                return oldFormat;
            }
        }
        return slugFrom(siteId, candidate, title, status, type, parent, Optional.of(before.id()));
    }

    /**
     * A published post of a flat type (or an attachment inheriting its parent's status) records the slug and the day
     * it had before the update, and forgets a recorded one it takes back.
     */
    void trackOldSlugAndDate(PostEntity row, PostSnapshot before, ZoneId zone) {
        boolean published =
                row.status.equals("publish") || (row.type.equals("attachment") && row.status.equals("inherit"));
        if (!published || types.hierarchical(row.type)) {
            return;
        }
        ObjectNode meta = MetaRows.copy(row.meta);
        boolean changed = false;
        if (!row.slug.equals(before.slug())) {
            changed |= track(meta, "_wp_old_slug", before.slug(), row.slug);
        }
        String previousDay = before.localDate(zone).toLocalDate().toString();
        String newDay = PostDates.localDate(Optional.ofNullable(row.publishedAt), row.modifiedAt, zone)
                .toLocalDate()
                .toString();
        if (!previousDay.equals(newDay)) {
            changed |= track(meta, "_wp_old_date", previousDay, newDay);
        }
        if (changed) {
            row.meta = meta;
        }
    }

    /** Records {@code previous} (unless empty or recorded) and forgets {@code current}; whether anything changed. */
    private static boolean track(ObjectNode meta, String key, String previous, String current) {
        List<String> recorded = new ArrayList<>();
        MetaRows.values(meta, key).forEach(value -> recorded.add(value.asText()));
        boolean changed = false;
        if (!PostRules.phpEmpty(previous) && !recorded.contains(previous)) {
            changed |= MetaRows.add(meta, key, TextNode.valueOf(previous), false);
        }
        if (recorded.contains(current)) {
            changed |= MetaRows.delete(meta, key, TextNode.valueOf(current));
        }
        return changed;
    }

    /**
     * Publishes a post: WordPress's {@code wp_publish_post}. Only the status changes; the update is not announced.
     * The caller holds the site's post lock and the row lock, and has checked that the post is not published.
     */
    void publish(SiteId siteId, PostEntity row) {
        PostSnapshot before = row.toSnapshot();
        written(() -> {
            row.status = "publish";
            posts.flush();
            events.published(siteId, PostDates.now(clock), before, row.toSnapshot());
            return null;
        });
    }

    /**
     * Runs the part of a save that writes: any exception marks the transaction for rollback before it propagates, so a
     * refusal after the first write never leaves a partly saved post for the caller to commit.
     */
    <T> T written(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            markRollbackOnly(failure);
            throw failure;
        }
    }

    /** Marks the transaction for rollback; a failure to do so is added to {@code failure} as suppressed. */
    void markRollbackOnly(RuntimeException failure) {
        try {
            registry.setRollbackOnly();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /**
     * The slug of a save before uniqueness: a candidate that is empty (or "0") gives none for draft, pending and
     * auto-draft and the sanitized title otherwise; any other candidate is sanitized.
     */
    String slugFrom(
            SiteId siteId,
            String candidate,
            String title,
            String status,
            String type,
            Optional<PostId> parent,
            Optional<PostId> post) {
        if (PostRules.phpEmpty(candidate)) {
            if (PostRules.NO_SLUG_STATUSES.contains(status)) {
                return "";
            }
            return slugSanitizer.sanitize(title, new SlugContext(siteId, post, type, status, parent, title));
        }
        return slugSanitizer.sanitize(candidate, new SlugContext(siteId, post, type, status, parent, candidate));
    }

    /**
     * A post that would keep an empty slug although its status has slugs gets its sanitized title, or its id when the
     * title gives nothing, made unique (WordPress's fallback after the row is written).
     */
    void lateSlug(SiteId siteId, PostEntity row) {
        if (!row.slug.isEmpty() || PostRules.NO_SLUG_STATUSES.contains(row.status)) {
            return;
        }
        Optional<PostId> id = Optional.of(PostId.of(row.id));
        Optional<PostId> parent = Optional.ofNullable(row.parentId).map(PostId::of);
        String text =
                slugSanitizer.sanitize(row.title, new SlugContext(siteId, id, row.type, row.status, parent, row.title));
        if (text.isEmpty()) {
            text = Long.toString(row.id);
        }
        row.slug = slugs.unique(siteId, text, id, Optional.of(""), row.status, row.type, parent);
    }

    /** Runs the write filters, then refuses a parent or author the filters made invalid and over-long values. */
    PostDraft filtered(SiteId siteId, PostDraft draft, PostWriteContext context, boolean update) {
        PostDraft result =
                Filters.apply(Filters.ordered(writeFilters), draft, (filter, value) -> filter.apply(value, context));
        if (!result.parent().equals(draft.parent()) && result.parent().isPresent()) {
            parent(siteId, result.parent().get().value(), context.before().map(PostSnapshot::id));
        }
        if (!result.author().equals(draft.author()) && result.author().isPresent()) {
            UserId author = result.author().get();
            if (!locks.lockProfile(author)) {
                throw new PostException("invalid_author", "Invalid author ID: " + author.value());
            }
        }
        PostRules.requireStorable(
                result.type(),
                result.status(),
                result.slug(),
                result.commentStatus(),
                result.pingStatus(),
                result.password(),
                "",
                update);
        return result;
    }

    void apply(PostEntity row, PostDraft draft) {
        row.type = draft.type();
        row.status = draft.status();
        row.slug = draft.slug();
        row.title = draft.title();
        row.content = draft.content();
        row.excerpt = draft.excerpt();
        row.authorId = draft.author().map(UserId::value).orElse(null);
        row.parentId = draft.parent().map(PostId::value).orElse(null);
        row.menuOrder = draft.menuOrder();
        row.publishedAt = draft.publishedAt().orElse(null);
        row.modifiedAt = draft.modifiedAt();
        row.commentStatus = draft.commentStatus();
        row.pingStatus = draft.pingStatus();
        row.password = draft.password();
    }

    /** An author id: 0 is none; a positive id must be an existing user. */
    Optional<UserId> author(long id) {
        if (id == 0) {
            return Optional.empty();
        }
        UserId user = UserId.of(id);
        if (profiles.find(user).isEmpty()) {
            throw new PostException("invalid_author", "Invalid author ID: " + id);
        }
        return Optional.of(user);
    }

    /** The stored parent of a post of the site; empty for a post without parent or a missing post. */
    Optional<PostId> storedParent(SiteId siteId, PostId post) {
        return posts.findPost(siteId, post).map(row -> row.parentId).map(PostId::of);
    }

    /** A parent id: 0 is none; a positive id must be a post of the site. */
    Optional<PostId> parent(SiteId siteId, long id, Optional<PostId> self) {
        if (id == 0) {
            return Optional.empty();
        }
        PostId parent = PostId.of(id);
        if (self.map(parent::equals).orElse(false)) {
            return Optional.of(parent);
        }
        if (posts.findPost(siteId, parent).isEmpty()) {
            throw new PostException("invalid_parent", "Invalid post parent ID: " + id);
        }
        return Optional.of(parent);
    }

    /** The site's home URL without trailing slash, with the http scheme. */
    String home() {
        String url = site.site().url("http").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String option(String name) {
        return site.options().string(name, "");
    }
}
