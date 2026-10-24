package org.quarkpress.revisions.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/**
 * The revisions service: capability checks first (WordPress's REST rules), then the site's post lock, then the parent
 * row, then revision rows in id order. A refused capability check or an input refusal raised before the first write
 * does not mark the caller's transaction for rollback; a refused lock does.
 */
@ApplicationScoped
public class JpaRevisions implements Revisions {

    @Inject
    SiteContext site;

    @Inject
    PostRepository postRows;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    Acting acting;

    @Inject
    PostLocks locks;

    @Inject
    RevisionWrites writes;

    @Inject
    RevisionRepository rows;

    @Inject
    RevisionQueries queries;

    @Inject
    RevisionPolicy policy;

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<RevisionSnapshot> save(PostId post) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        PostEntity row = lockedParent(
                siteId, post, stored -> acting.require(siteId, "edit_post", Optional.of(facts.of(stored))));
        return writes.saveRevision(siteId, row.toSnapshot());
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<RevisionSnapshot> find(RevisionId revision) {
        Objects.requireNonNull(revision, "revision");
        SiteId siteId = site.siteId();
        Optional<RevisionEntity> row = rows.findRevision(siteId, revision);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        readableParent(siteId, PostId.of(row.get().postId));
        return row.map(RevisionEntity::toSnapshot);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<RevisionSnapshot> ofPost(PostId post, RevisionQuery query) {
        Objects.requireNonNull(query, "query");
        SiteId siteId = site.siteId();
        PostSnapshot parent = readableParent(siteId, post);
        if (query.checkEnabled() && !policy.enabled(parent)) {
            return List.of();
        }
        return queries.ofPost(siteId, post, query).stream()
                .map(RevisionEntity::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<RevisionSnapshot> autosaveOf(PostId post, Optional<UserId> user) {
        Objects.requireNonNull(user, "user");
        SiteId siteId = site.siteId();
        readableParent(siteId, post);
        return (user.isPresent() ? rows.autosaveOfAuthor(siteId, post, user) : queries.latestAutosave(siteId, post))
                .map(RevisionEntity::toSnapshot);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<RevisionSnapshot> autosavesOf(PostId post) {
        SiteId siteId = site.siteId();
        readableParent(siteId, post);
        return queries
                .ofPost(
                        siteId,
                        post,
                        RevisionQuery.builder()
                                .autosaves(RevisionQuery.Autosaves.ONLY)
                                .checkEnabled(false)
                                .build())
                .stream()
                .map(RevisionEntity::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public RevisionCount latestAndCount(PostId post) {
        SiteId siteId = site.siteId();
        PostSnapshot parent = readableParent(siteId, post);
        if (!policy.enabled(parent)) {
            throw RevisionException.notEnabled();
        }
        return queries.latestAndCount(siteId, post);
    }

    /** The post, which the caller must be able to edit; a missing post, or one of another site, is refused. */
    PostSnapshot readableParent(SiteId siteId, PostId post) {
        Objects.requireNonNull(post, "post");
        PostSnapshot found = postRows.findPost(siteId, post)
                .map(PostEntity::toSnapshot)
                .orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(found)));
        return found;
    }

    /**
     * The stored post, locked for a write: read and checked without locks (a missing post, or one of another site, is
     * {@code invalid_post}), then the site's post lock is taken, and the post is read again under its row lock and
     * checked again, since it may have changed meanwhile.
     */
    PostEntity lockedParent(SiteId siteId, PostId post, Consumer<PostSnapshot> checks) {
        PostSnapshot unlocked = postRows.findPost(siteId, post)
                .map(PostEntity::toSnapshot)
                .orElseThrow(() -> PostException.invalidPost(post));
        checks.accept(unlocked);
        locks.lock(siteId);
        PostEntity row = postRows.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        checks.accept(row.toSnapshot());
        return row;
    }
}
