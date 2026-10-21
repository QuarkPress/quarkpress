package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.TenantIds;
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

    @Inject
    FrontPages frontPages;

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
        boolean claimed = !status.equals("trash")
                && !PostRules.phpEmpty(slug)
                && claimFromTrashed(siteId, slug, Optional.empty());
        try {
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
            PostDraft saved = filtered(
                    siteId, draft, new PostWriteContext(siteId, Optional.empty(), acting.user(), false), false);
            PostEntity row = PostEntity.create(siteId);
            apply(row, saved);
            row.createdAt = clock.now().truncatedTo(ChronoUnit.MICROS);
            return written(() -> {
                posts.persist(row);
                posts.flush();
                row.guid = PostRules.guid(home(), row.type, row.id);
                lateSlug(siteId, row);
                posts.flush();
                PostSnapshot after = row.toSnapshot();
                events.saved(siteId, now, "new", Optional.empty(), after, false);
                return after;
            });
        } catch (RuntimeException failure) {
            // a trashed post renamed above is a write: the failed insert must not leave it for the caller to commit
            if (claimed) {
                markRollbackOnly(failure);
            }
            throw failure;
        }
    }

    /**
     * Updates a post: WordPress's {@code wp_update_post}. The caller holds the site's post lock and the row lock.
     * Fields not given keep their stored values; every text field is filtered again with the caller's settings.
     */
    PostSnapshot update(SiteId siteId, PostEntity row, PostChanges changes) {
        return update(siteId, row, changes, stored -> {}, () -> {});
    }

    /**
     * {@link #update(SiteId, PostEntity, PostChanges)} with {@code beforeWrite} run on the row first once the save
     * writes, so a save refused before writing leaves the row as it was, and {@code afterWrite} run once the row is
     * written, before the save is announced.
     */
    PostSnapshot update(
            SiteId siteId, PostEntity row, PostChanges changes, Consumer<PostEntity> beforeWrite, Runnable afterWrite) {
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
        boolean wrote = false;
        try {
            for (PostId member : decision.cut()) {
                PostEntity memberRow =
                        posts.lockPost(siteId, member).orElseThrow(() -> PostException.invalidPost(member));
                update(siteId, memberRow, PostChanges.builder().parent(0).build());
                wrote = true;
            }
            TrashedSlug trashed = trashSteps(siteId, before, slug, status);
            wrote |= trashed.claimed();
            slug = slugs.unique(
                    siteId, trashed.slug(), Optional.of(before.id()), Optional.of(before.slug()), status, type, parent);
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
                beforeWrite.accept(row);
                trashed.recordOn(row);
                apply(row, saved);
                lateSlug(siteId, row);
                trackOldSlugAndDate(row, before, zone);
                posts.flush();
                afterWrite.run();
                PostSnapshot after = row.toSnapshot();
                events.saved(siteId, now, before.status(), Optional.of(before), after, true);
                return after;
            });
        } catch (RuntimeException failure) {
            // a loop member re-parented or a trashed post renamed above is a write: the failed update must not leave
            // it for the caller to commit
            if (wrote) {
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

    /** Changes a post's stickiness and announces it; the caller holds the row lock. */
    void setSticky(SiteId siteId, PostEntity row, boolean sticky) {
        written(() -> {
            row.sticky = sticky;
            posts.flush();
            events.sticky(siteId, PostDates.now(clock), PostId.of(row.id), sticky);
            return null;
        });
    }

    /**
     * The slug a save takes into uniqueness after its trash steps, and the desired-slug record the save writes:
     * {@code forgetDesired} removes {@code _wp_desired_post_slug}, {@code desired} adds that value to it.
     * {@code claimed} tells whether trashed posts were renamed, which is a write.
     */
    record TrashedSlug(String slug, boolean claimed, boolean forgetDesired, Optional<String> desired) {

        /** Writes the desired-slug record on the saved row. */
        void recordOn(PostEntity row) {
            if (!forgetDesired && desired.isEmpty()) {
                return;
            }
            ObjectNode meta = MetaRows.copy(row.meta);
            if (forgetDesired) {
                MetaRows.delete(meta, "_wp_desired_post_slug");
            }
            desired.ifPresent(value -> MetaRows.add(meta, "_wp_desired_post_slug", TextNode.valueOf(value), false));
            row.meta = meta;
        }
    }

    /**
     * The trash steps of an update, before uniqueness. Leaving the trash restores the recorded desired slug, which
     * then wins over the candidate (an empty one is neither restored nor forgotten). A post that will not be trashed
     * takes a non-empty slug back from trashed posts of the searchable types. Entering the trash from another status
     * gives the stored slug the trashed suffix and records the stored slug, unless it already has the suffix.
     */
    TrashedSlug trashSteps(SiteId siteId, PostSnapshot before, String slug, String status) {
        String result = slug;
        boolean forget = false;
        if (before.status().equals("trash") && !status.equals("trash")) {
            Optional<String> desired =
                    before.metaText("_wp_desired_post_slug").filter(value -> !PostRules.phpEmpty(value));
            if (desired.isPresent()) {
                forget = true;
                result = desired.get();
            }
        }
        boolean claimed = !status.equals("trash")
                && !PostRules.phpEmpty(result)
                && claimFromTrashed(siteId, result, Optional.of(before.id()));
        Optional<String> desired = Optional.empty();
        if (status.equals("trash") && !before.status().equals("trash")) {
            result = UniqueSlugs.withTrashedSuffix(before.slug());
            if (!UniqueSlugs.isTrashedSlug(before.slug())) {
                desired = Optional.of(before.slug());
            }
        }
        return new TrashedSlug(result, claimed, forget, desired);
    }

    /**
     * Renames the trashed posts of the searchable types, other than {@code exclude}, whose slug is {@code slug}, as
     * trashing them would have; whether any was renamed.
     */
    boolean claimFromTrashed(SiteId siteId, String slug, Optional<PostId> exclude) {
        List<String> searchable = types.searchable();
        // a holder of a slug with the trashed suffix would keep it: nothing to rename
        if (searchable.isEmpty() || UniqueSlugs.isTrashedSlug(slug)) {
            return false;
        }
        List<PostEntity> holders = posts.find(
                        "siteId = ?1 and status = 'trash' and slug = ?2 and id <> ?3 and type in ?4",
                        TenantIds.param(siteId),
                        slug,
                        exclude.map(PostId::value).orElse(-1L),
                        searchable)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
        boolean renamed = false;
        for (PostEntity holder : holders) {
            // a holder already in this transaction's context keeps its old state on a query: read it again
            posts.getEntityManager().refresh(holder, LockModeType.PESSIMISTIC_WRITE);
            if (!holder.status.equals("trash") || !holder.slug.equals(slug)) {
                continue;
            }
            ObjectNode meta = MetaRows.copy(holder.meta);
            MetaRows.add(meta, "_wp_desired_post_slug", TextNode.valueOf(holder.slug), false);
            holder.meta = meta;
            holder.slug = UniqueSlugs.withTrashedSuffix(holder.slug);
            renamed = true;
        }
        return renamed;
    }

    /**
     * Trashes a post: WordPress's {@code wp_trash_post} after its checks. The caller holds the site's post lock and
     * the row lock and has checked the capability and that the post is not trashed. Empty when the save was refused
     * before writing anything; then nothing changed.
     */
    Optional<PostSnapshot> trash(SiteId siteId, PostEntity row) {
        PostSnapshot before = row.toSnapshot();
        Instant now = PostDates.now(clock);
        boolean[] unstuck = new boolean[1];
        return unlessRefused(() -> update(
                        siteId,
                        row,
                        PostChanges.builder().status("trash").build(),
                        stored -> {
                            if (stored.sticky) {
                                stored.sticky = false;
                                unstuck[0] = true;
                            }
                            ObjectNode meta = MetaRows.copy(stored.meta);
                            MetaRows.add(meta, "_wp_trash_meta_status", TextNode.valueOf(before.status()), false);
                            MetaRows.add(meta, "_wp_trash_meta_time", LongNode.valueOf(now.getEpochSecond()), false);
                            stored.meta = meta;
                        },
                        // as in WordPress, the reading settings are reset, then the unstick is announced, before the
                        // save is; both come after the post row is written, since option rows come last in the lock
                        // order
                        () -> {
                            frontPages.forget(before);
                            if (unstuck[0]) {
                                events.sticky(siteId, now, before.id(), false);
                            }
                        }))
                .map(after -> written(() -> {
                    events.trashed(siteId, now, after);
                    return before;
                }));
    }

    /**
     * Restores a trashed post: WordPress's {@code wp_untrash_post} after its checks, as a draft (an attachment as
     * inherit). The caller holds the site's post lock and the row lock and has checked the capability and that the
     * post is trashed. Empty when the save was refused before writing anything; then nothing changed.
     */
    Optional<PostSnapshot> untrash(SiteId siteId, PostEntity row) {
        PostSnapshot before = row.toSnapshot();
        Instant now = PostDates.now(clock);
        String restored = row.type.equals("attachment") ? "inherit" : "draft";
        Optional<String> previous =
                before.metaText("_wp_trash_meta_status").filter(status -> !PostRules.phpEmpty(status));
        return unlessRefused(() -> update(
                        siteId,
                        row,
                        PostChanges.builder().status(restored).build(),
                        stored -> {
                            ObjectNode meta = MetaRows.copy(stored.meta);
                            MetaRows.delete(meta, "_wp_trash_meta_status");
                            MetaRows.delete(meta, "_wp_trash_meta_time");
                            stored.meta = meta;
                        },
                        () -> {}))
                .map(after -> written(() -> {
                    events.restored(siteId, now, after, previous);
                    return before;
                }));
    }

    /**
     * Deletes a post for good: WordPress's {@code wp_delete_post} after its checks ({@code wp_delete_attachment} for an
     * attachment). The caller holds the site's post lock and the row lock and has checked the capability. The delete is
     * announced before anything changes; then its children are handed down or let go, the row goes with its meta, and,
     * in WordPress's order, the reading settings naming the page are reset, a sticky post is announced as unstuck (its
     * flag went with the row) and the privacy policy page setting naming the page is reset; option rows come last in
     * the lock order. Then the delete is announced as done. Any failure marks the transaction for rollback, so a failed
     * delete changes nothing.
     */
    void delete(SiteId siteId, PostEntity row) {
        Instant now = PostDates.now(clock);
        PostSnapshot before = row.toSnapshot();
        written(() -> {
            events.deleting(siteId, now, before);
            releaseChildren(siteId, before);
            // an observer of the delete may have changed the post: what was stuck is read from the row
            boolean sticky = row.sticky;
            posts.delete(row);
            posts.flush();
            frontPages.forget(before);
            if (sticky) {
                events.sticky(siteId, now, before.id(), false);
            }
            frontPages.forgetPrivacyPolicyPage(before);
            events.deleted(siteId, now, before.id(), before.type());
            return null;
        });
    }

    /**
     * The children of a post being deleted, locked in id order under the site's post lock, which already serializes
     * every writer of the site's posts. Same-type children of a hierarchical type and attachments get the post's
     * parent, as in WordPress; the children of an attachment, which WordPress leaves naming the missing post, and
     * every other child are left without parent, since the parent key cascades the delete to children still naming
     * the post. A post that is its own parent hands down none. As in WordPress only the parent changes: not the
     * version, so an update made with a version read before the delete still succeeds, nor the modification date.
     */
    private void releaseChildren(SiteId siteId, PostSnapshot post) {
        long id = post.id().value();
        Object site = TenantIds.param(siteId);
        Long handedDown =
                post.parent().map(PostId::value).filter(parent -> parent != id).orElse(null);
        boolean attachment = post.type().equals("attachment");
        boolean hierarchical = types.hierarchical(post.type());
        // the site condition is defence in depth: the composite parent key already keeps children on the post's site
        List<PostEntity> children = posts.find("siteId = ?1 and parentId = ?2 and id <> ?2 order by id", site, id)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
        List<Long> follow = new ArrayList<>();
        List<Long> release = new ArrayList<>();
        for (PostEntity child : children) {
            // a child already in this transaction's context keeps its old state on a query: read it again
            posts.getEntityManager().refresh(child, LockModeType.PESSIMISTIC_WRITE);
            if (child.parentId == null || child.parentId != id) {
                continue;
            }
            boolean follows = !attachment
                    && (child.type.equals("attachment") || (hierarchical && child.type.equals(post.type())));
            (follows && handedDown != null ? follow : release).add(child.id);
        }
        // plain updates of the locked rows, not versioned ones: WordPress changes nothing but the parent
        if (!follow.isEmpty()) {
            posts.update("parentId = ?1 where siteId = ?2 and id in ?3", handedDown, site, follow);
        }
        if (!release.isEmpty()) {
            posts.update("parentId = null where siteId = ?1 and id in ?2", site, release);
        }
        for (PostEntity child : children) {
            posts.getEntityManager().refresh(child);
        }
    }

    /**
     * The saved post, or empty when the save was refused before it wrote anything (the transaction is then not
     * marked for rollback); a refusal after a write propagates.
     */
    private Optional<PostSnapshot> unlessRefused(Supplier<PostSnapshot> save) {
        try {
            return Optional.of(save.get());
        } catch (PostException refused) {
            // marked: the save wrote (or the caller's transaction was doomed already); either way not a clean refusal
            if (markedForRollback()) {
                throw refused;
            }
            return Optional.empty();
        }
    }

    private boolean markedForRollback() {
        return registry.getTransactionStatus() == Status.STATUS_MARKED_ROLLBACK;
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
                result.title(),
                result.excerpt(),
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
