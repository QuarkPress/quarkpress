package org.quarkpress.posts.runtime;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Session;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.security.runtime.JpaMemberships;

/**
 * The lock every post write of a site takes after its capability checks and before it locks or writes post rows, held
 * until its transaction ends: post writes of one site apply one after the other, so slug decisions never race. The
 * site's lock row is created on demand with an insert that cannot fail on its key. The request must be bound to the
 * site (native SQL is not seen by the site listener).
 *
 * <p>Locks are taken in this order: the network, a user's profile, a membership, this site lock, post rows, and option
 * rows last. Capability checks run before the lock is taken, so a caller who may not write never waits for it nor
 * makes others wait. The lock is recorded in {@link HeldLocks}: a scope spawned while it is held that would wait for
 * it is refused at once.
 *
 * <p>Creating the lock row checks the site's row through its foreign key. Write a new site's posts only after the
 * transaction that creates the site commits: a site scope spawned before then fails on PostgreSQL (the site row is
 * not yet visible) and waits on MySQL until the lock-wait timeout. The same MySQL wait affects a scope spawned by a
 * transaction that changed the site row.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class PostLocks {

    private static final String POSTGRESQL_ENSURE =
            "insert into qp_post_lock (site_id) values (?1) on conflict (site_id) do nothing";
    private static final String MYSQL_ENSURE =
            "insert into qp_post_lock (site_id) values (?1) on duplicate key update site_id = site_id";
    private static final String LOCK = "select site_id from qp_post_lock where site_id = ?1 for update";
    private static final String PROFILE = "select id from qp_user_profile where id = ?1";
    private static final String POSTGRESQL_PROFILE = PROFILE + " for key share";
    private static final String MYSQL_PROFILE = PROFILE + " for share";

    @Inject
    PostRepository posts;

    /**
     * Locks the posts of {@code site} until the transaction ends. Refused with {@link IllegalStateException} when a
     * transaction up this thread's scope chain holds the lock (the two would wait for each other) or when the request
     * is bound to another site.
     */
    public void lock(SiteId site) {
        Binding bound = Arc.container().instance(SiteBinding.class).get().current();
        if (!bound.equals(Binding.site(site))) {
            throw new IllegalStateException("the post lock of site " + site.value() + " taken under " + bound);
        }
        String name = name(site);
        if (HeldLocks.heldBySpawner(name)) {
            throw new IllegalStateException("the posts of site " + site.value()
                    + " are locked by a transaction that waits for this change: change the posts of one site in one"
                    + " transaction at a time");
        }
        Session session = posts.getSession();
        session.createNativeMutationQuery(Dialects.postgresql(session) ? POSTGRESQL_ENSURE : MYSQL_ENSURE)
                .setParameter(1, site.value())
                .executeUpdate();
        session.createNativeQuery(LOCK, Long.class)
                .setParameter(1, site.value())
                .getSingleResult();
        HeldLocks.record(name);
    }

    /**
     * Share-locks the profile of {@code user} until the transaction ends, so that it cannot be deleted while a post
     * names it as author; returns whether the profile exists (a missing profile is not locked). Take it before
     * {@link #lock}: a profile comes before the site lock in the lock order. A write filter that changes the author,
     * and a post created from inside another post write of the site, lock the profile after the site lock instead;
     * they are still refused with {@link IllegalStateException}, as every caller is, when a transaction up this
     * thread's scope chain holds the profile (the two would wait for each other).
     */
    public boolean lockProfile(UserId user) {
        String name = JpaMemberships.profileLock(user);
        if (HeldLocks.heldBySpawner(name)) {
            throw new IllegalStateException("the profile of user " + user.value()
                    + " is locked by a transaction that waits for this post write: write the post in a separate"
                    + " transaction");
        }
        Session session = posts.getSession();
        // a missing row is not locked: on MySQL that would lock the gap new profiles are inserted into
        if (!exists(session, PROFILE, user)) {
            return false;
        }
        boolean found = exists(session, Dialects.postgresql(session) ? POSTGRESQL_PROFILE : MYSQL_PROFILE, user);
        HeldLocks.record(name);
        return found;
    }

    private static boolean exists(Session session, String sql, UserId user) {
        return !session.createNativeQuery(sql, Long.class)
                .setParameter(1, user.value())
                .getResultList()
                .isEmpty();
    }

    /** The name under which a transaction records the lock ({@code HeldLocks}). */
    public static String name(SiteId site) {
        return "qp_post_lock:" + site.value();
    }
}
