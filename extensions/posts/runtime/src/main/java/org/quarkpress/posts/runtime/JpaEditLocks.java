package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.security.UserProfiles;

/**
 * The editor's post lock on the {@code _edit_lock} meta. A write checks {@code edit_post} on the post it finds,
 * refuses the system identity, then takes the site's post lock and the row lock, checks again on the locked row and
 * decides on the value read under the lock; the value is written through {@link ProtectedMeta}, so neither the
 * post's modification date nor its version changes. Reads take no lock. Refusals come before any lock, so they do
 * not mark the caller's transaction for rollback.
 */
@ApplicationScoped
public class JpaEditLocks implements org.quarkpress.posts.PostLocks {

    /** The meta key of the lock. */
    public static final String EDIT_LOCK = "_edit_lock";

    /** The meta key of the last editor, the holder of a lock value without user part. */
    public static final String EDIT_LAST = "_edit_last";

    /** A released lock expires this many seconds after the release. */
    private static final long RELEASE_GRACE_SECONDS = 5;

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks siteLocks;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    UserProfiles profiles;

    @Inject
    QuarkPressClock clock;

    @Inject
    PostsConfig config;

    @Inject
    ProtectedMeta meta;

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<UserId> holder(PostId post, Optional<UserId> acting) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(acting, "acting");
        return posts.findPost(site.siteId(), post).flatMap(row -> holderOf(row, acting));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Take take(PostId post) {
        return takeUnlessHeld(post);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Take refresh(PostId post) {
        return takeUnlessHeld(post);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Lock takeOver(PostId post) {
        SiteId siteId = site.siteId();
        UserId caller = caller(siteId, post);
        PostEntity row = locked(siteId, post);
        return set(siteId, row, caller, clock.now());
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean release(PostId post, String expected) {
        Objects.requireNonNull(expected, "expected");
        SiteId siteId = site.siteId();
        UserId caller = caller(siteId, post);
        Optional<Lock> parsed = Lock.parse(expected);
        if (parsed.isEmpty() || !parsed.get().user().equals(caller)) {
            return false;
        }
        PostEntity row = locked(siteId, post);
        if (meta.text(row, EDIT_LOCK).filter(expected::equals).isEmpty()) {
            return false;
        }
        Instant since = clock.now().minusSeconds(windowSeconds()).plusSeconds(RELEASE_GRACE_SECONDS);
        set(siteId, row, caller, since);
        return true;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<Lock> current(PostId post) {
        Objects.requireNonNull(post, "post");
        return posts.findPost(site.siteId(), post)
                .flatMap(row -> meta.text(row, EDIT_LOCK))
                .flatMap(Lock::parse);
    }

    private Take takeUnlessHeld(PostId post) {
        SiteId siteId = site.siteId();
        UserId caller = caller(siteId, post);
        PostEntity row = locked(siteId, post);
        Optional<UserId> holder = holderOf(row, Optional.of(caller));
        if (holder.isPresent()) {
            return new Take.Held(holder.get());
        }
        return new Take.Taken(set(siteId, row, caller, clock.now()));
    }

    /** Checks the caller on the post as found, without locks; the user who will hold the lock. */
    private UserId caller(SiteId siteId, PostId post) {
        Objects.requireNonNull(post, "post");
        PostEntity unlocked = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(unlocked.toSnapshot())));
        if (acting.system()) {
            throw new IllegalArgumentException("the system identity cannot hold a post lock");
        }
        return acting.user().orElseThrow(() -> new IllegalArgumentException("the caller has no user profile"));
    }

    /** The row under the site's post lock and its row lock, checked again. */
    private PostEntity locked(SiteId siteId, PostId post) {
        siteLocks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
        return row;
    }

    private Lock set(SiteId siteId, PostEntity row, UserId user, Instant since) {
        Lock lock = new Lock(user, Instant.ofEpochSecond(since.getEpochSecond()));
        meta.put(siteId, row, EDIT_LOCK, TextNode.valueOf(lock.value()));
        return lock;
    }

    /**
     * WordPress's lock check on a row: the time part must be non-zero and strictly later than now minus the window,
     * the user part (or {@code _edit_last} when there is none) must name an existing user other than {@code acting}.
     */
    private Optional<UserId> holderOf(PostEntity row, Optional<UserId> acting) {
        Optional<String> value = meta.text(row, EDIT_LOCK);
        if (value.isEmpty() || value.get().isEmpty()) {
            return Optional.empty();
        }
        String text = value.get();
        int colon = text.indexOf(':');
        long time = PhpInts.of(colon < 0 ? text : text.substring(0, colon));
        long user = colon < 0 ? editLast(row) : PhpInts.of(text.substring(colon + 1));
        if (time == 0 || time <= clock.now().getEpochSecond() - windowSeconds()) {
            return Optional.empty();
        }
        if (user <= 0 || acting.map(me -> me.value() == user).orElse(false)) {
            return Optional.empty();
        }
        UserId holder = UserId.of(user);
        return profiles.find(holder).map(profile -> holder);
    }

    private static long editLast(PostEntity row) {
        List<JsonNode> values = MetaRows.values(row.meta, EDIT_LAST);
        return values.isEmpty() ? 0 : PhpInts.of(values.get(0));
    }

    private long windowSeconds() {
        return config.lockWindow().toSeconds();
    }
}
