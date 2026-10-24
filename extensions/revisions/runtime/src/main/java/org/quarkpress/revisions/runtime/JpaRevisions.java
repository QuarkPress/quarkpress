package org.quarkpress.revisions.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostRepository;
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
