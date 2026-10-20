package org.quarkpress.posts.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.WordPressKeys;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.PostTypeCapabilities;

/**
 * The posts service: capability checks first (WordPress's REST rules), then a share lock on the author's profile, then
 * the site's post lock, then the save path. A write to a stored post reads it and checks it unlocked, then takes the
 * locks, reads it again under its row lock and checks it again. Invalid input refused before the first write throws
 * {@code PostException} without marking the caller's transaction for rollback; a refused read does not mark it either.
 * A stale-version refusal is raised before any write and does not mark it either. Once a save has written, any
 * exception (a refusal included) marks the transaction for rollback before it propagates.
 */
@ApplicationScoped
public class JpaPosts implements Posts {

    /** Statuses a caller may set only with the type's publish capability. */
    static final Set<String> PUBLISHING = Set.of("publish", "future", "private");

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostWrites writes;

    @Inject
    PostLocks locks;

    @Inject
    Acting acting;

    @Inject
    PostTypes types;

    @Inject
    PostCapabilityFacts facts;

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public PostSnapshot create(PostChanges changes) {
        Objects.requireNonNull(changes, "changes");
        SiteId siteId = site.siteId();
        String type = PostRules.type(changes.type().orElse(""));
        PostTypeCapabilities names = types.capabilitiesOrPost(type);
        acting.require(siteId, names.createPosts(), Optional.empty());
        requirePublishing(siteId, changes.status(), names);
        requireAuthor(siteId, changes.author(), names);
        lockAuthor(changes.author());
        locks.lock(siteId);
        return writes.insert(siteId, changes);
    }

    @Override
    @Transactional(dontRollbackOn = {IllegalArgumentException.class, OptimisticLockException.class})
    public PostSnapshot update(PostId post, PostChanges changes) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(changes, "changes");
        SiteId siteId = site.siteId();
        PostEntity row = lockedRow(siteId, post, changes.author(), stored -> {
            acting.require(siteId, "edit_post", Optional.of(facts.of(stored)));
            String type = changes.type().map(PostRules::type).orElse(stored.type());
            PostTypeCapabilities names = types.capabilitiesOrPost(type);
            if (!type.equals(stored.type())) {
                acting.require(siteId, names.createPosts(), Optional.empty());
            }
            requirePublishing(siteId, changes.status(), names);
            requireAuthor(siteId, changes.author(), names);
        });
        if (changes.expectedVersion().isPresent() && changes.expectedVersion().getAsInt() != row.version) {
            throw new OptimisticLockException("post " + post.value() + " changed since version "
                    + changes.expectedVersion().getAsInt() + "; it is now at version " + row.version);
        }
        return writes.update(siteId, row, changes);
    }

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean publish(PostId post) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        PostEntity row = lockedRow(siteId, post, "publish_post");
        if (row.status.equals("publish")) {
            return false;
        }
        writes.publish(siteId, row);
        return true;
    }

    /** {@link #lockedRow(SiteId, PostId, OptionalLong, Consumer)} checking one capability on the post. */
    PostEntity lockedRow(SiteId siteId, PostId post, String capability) {
        return lockedRow(
                siteId,
                post,
                OptionalLong.empty(),
                stored -> acting.require(siteId, capability, Optional.of(facts.of(stored))));
    }

    /**
     * The stored post, locked for the write: read and checked without locks (a missing post, or one of another site,
     * is {@code invalid_post}), then the given author's profile and the site's post lock are taken, and the post is
     * read again under its row lock and checked again, since it may have changed meanwhile.
     */
    PostEntity lockedRow(SiteId siteId, PostId post, OptionalLong author, Consumer<PostSnapshot> checks) {
        PostSnapshot unlocked = posts.findPost(siteId, post)
                .map(PostEntity::toSnapshot)
                .orElseThrow(() -> PostException.invalidPost(post));
        checks.accept(unlocked);
        lockGivenAuthor(author);
        locks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        checks.accept(row.toSnapshot());
        return row;
    }

    @Override
    @Transactional(dontRollbackOn = {ForbiddenException.class, UnauthorizedException.class})
    public Optional<PostSnapshot> find(PostId post) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        Optional<PostSnapshot> found = posts.findPost(siteId, post).map(PostEntity::toSnapshot);
        found.ifPresent(snapshot -> acting.requireReadable(siteId, facts.of(snapshot)));
        return found;
    }

    /**
     * Share-locks the profile of the post's author (the given one, else the caller) before the site's post lock, as
     * the lock order requires; an author whose profile does not exist is refused.
     */
    void lockAuthor(OptionalLong given) {
        Optional<UserId> author = given.isPresent() ? givenAuthor(given) : acting.user();
        lockProfile(author);
    }

    /** Share-locks the profile of a given author (none when not given or 0), as {@link #lockAuthor} does. */
    void lockGivenAuthor(OptionalLong given) {
        lockProfile(givenAuthor(given));
    }

    private static Optional<UserId> givenAuthor(OptionalLong given) {
        return given.isPresent()
                ? Optional.of(given.getAsLong()).filter(id -> id != 0).map(UserId::of)
                : Optional.empty();
    }

    private void lockProfile(Optional<UserId> author) {
        if (author.isPresent() && !locks.lockProfile(author.get())) {
            throw new PostException(
                    "invalid_author", "Invalid author ID: " + author.get().value());
        }
    }

    /** A requested status of publish, future or private needs the type's publish capability. */
    void requirePublishing(SiteId siteId, Optional<String> status, PostTypeCapabilities names) {
        if (status.isPresent() && PUBLISHING.contains(WordPressKeys.sanitize(status.get()))) {
            acting.require(siteId, names.publishPosts(), Optional.empty());
        }
    }

    /** An author other than the caller (none included) needs the type's edit-others capability. */
    void requireAuthor(SiteId siteId, OptionalLong author, PostTypeCapabilities names) {
        if (author.isEmpty()) {
            return;
        }
        long id = author.getAsLong();
        boolean self = acting.user().map(user -> user.value() == id).orElse(false);
        if (!self) {
            acting.require(siteId, names.editOthersPosts(), Optional.empty());
        }
    }
}
