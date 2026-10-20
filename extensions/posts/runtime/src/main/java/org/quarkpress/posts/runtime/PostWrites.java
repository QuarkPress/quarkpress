package org.quarkpress.posts.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
     * Runs the part of a save that writes: any exception marks the transaction for rollback before it propagates, so a
     * refusal after the first write never leaves a partly saved post for the caller to commit.
     */
    <T> T written(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            try {
                registry.setRollbackOnly();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
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
