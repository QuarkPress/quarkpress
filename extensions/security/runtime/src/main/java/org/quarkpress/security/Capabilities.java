package org.quarkpress.security;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * Answers WordPress capability checks. A check without an explicit site is for the binding in effect: the site the
 * request (or the {@code SiteScope}) is bound to; for the main site when bound to the network; for no site when
 * unresolved, where only {@code exist} is held (and, in a network, network administrators pass). Work in a
 * {@code SiteScope} for another site runs without the caller's identity. Checks read the database and must not be
 * made on an event-loop thread, except through an identity's permission check, which moves its reads off it.
 *
 * <p>Nothing is cached: each check reads the membership, the site's role table, the network administrators and the
 * options it needs, each in a short transaction of its own when the caller has none, so one check may combine reads
 * from either side of a concurrent change. A check made inside the caller's transaction joins it and sees what that
 * transaction sees: on MySQL (repeatable read) a change committed by others after the transaction's first read, such as
 * a revoked network administrator, is not visible to checks until that transaction ends. A user id without a profile
 * counts as a logged-out visitor.
 */
public interface Capabilities {

    /** The current identity's answer for the current binding (WordPress's {@code current_user_can}). */
    boolean can(String capability);

    boolean can(String capability, CapObject object);

    /** {@code identity}'s answer for the current binding; for {@code @PermissionChecker} methods. */
    boolean can(SecurityIdentity identity, String capability, Optional<CapObject> object);

    /**
     * {@code identity}'s answer on {@code site} (WordPress's {@code current_user_can_for_site}); on a single site the
     * site id is ignored and the answer is for the only site.
     */
    boolean canForSite(SecurityIdentity identity, SiteId site, String capability, Optional<CapObject> object);

    /** A user's answer on {@code site} (WordPress's {@code user_can_for_site}); an unknown user holds only exist. */
    boolean userCan(UserId user, SiteId site, String capability, Optional<CapObject> object);

    /** A logged-out visitor's answer on {@code site}. */
    boolean anonymousCan(SiteId site, String capability, Optional<CapObject> object);

    /**
     * WordPress's {@code is_super_admin}: in a network, a network administrator; on a single site, a user who can
     * delete users on the main site (which gives no bypass).
     */
    boolean isSuperAdmin(UserId user);
}
