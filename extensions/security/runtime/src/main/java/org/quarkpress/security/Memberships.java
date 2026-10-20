package org.quarkpress.security;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * Users' memberships of sites, as WordPress's user API manages them. Changes require {@code promote_user} on the
 * target user ({@link #remove} {@code remove_user}), checked for the current identity on the target site, and run in a
 * transaction bound to that site (joining the caller's when it is bound to that site). Role changes fire
 * {@link MembershipChanged}. On a single site every membership is of the only site, whatever site id is given.
 *
 * <p>What a change gives is checked as WordPress's user screens do, where WordPress's functions check nothing: a role
 * must exist in the site's role table ({@link IllegalArgumentException} otherwise), and only the system and network
 * administrators may give any role or capability. Anyone else ({@code ForbiddenException}) may give only roles whose
 * capabilities they hold on the site and grant only capabilities they hold, and never a network capability
 * ({@code manage_network} and the like, granted or denied); on a single site, a user who can delete users may grant any
 * capability but a network one.
 *
 * <p>A change is written to the database before it returns: {@link #find} and capability checks made later in the same
 * transaction see it. {@link #sitesOf} reads committed memberships only, on a thread of its own. {@link #add} and
 * {@link #remove} of a user after another change of that user on a different site, in one caller transaction, are
 * refused with {@link IllegalStateException} (they would wait for the first change's lock on the user's profile, held
 * by the transaction that waits for it). A change that meets a deadlock or a lock timeout fails with
 * {@code jakarta.persistence.PessimisticLockException}: retry the whole transaction.
 */
public interface Memberships {

    Optional<Membership> find(SiteId site, UserId user);

    /** Every membership of the user, by site id, as committed. */
    List<Membership> sitesOf(UserId user);

    /**
     * WordPress's "member of the site": in a network, the user has a membership row on a site that is not archived,
     * spam or deleted (having a role is not needed); on a single site, every existing user.
     */
    boolean isMember(SiteId site, UserId user);

    /**
     * Adds a user to a site with {@code role} (empty: a member without role), replacing the user's roles there and
     * keeping individual capabilities; a user without a primary site gets this one. Refuses an unknown user or site
     * with {@link IllegalArgumentException}.
     */
    Membership add(SiteId site, UserId user, String role);

    /**
     * Ends a membership. A primary site that was this site moves to the user's first other site (by id) that is not
     * archived, spam or deleted; a user left without such a site has no primary site. False when not a member.
     */
    boolean remove(SiteId site, UserId user);

    /** Replaces the user's roles with {@code role}; nothing changes when it already is their only role. */
    Membership setRole(SiteId site, UserId user, String role);

    /**
     * Adds a role; nothing changes when it already is the user's. An empty role is refused with
     * {@link IllegalArgumentException} and writes nothing.
     */
    Membership addRole(SiteId site, UserId user, String role);

    /** Removes a role; empty when the user is not a member. */
    Optional<Membership> removeRole(SiteId site, UserId user, String role);

    /**
     * Sets an individual capability (false is a denial; a role key keeps counting as a role). Storing a role key,
     * granted or denied, gives the role and is checked as giving the role is. Granting a key listed among the user's
     * roles keeps their order.
     */
    Membership setCapability(SiteId site, UserId user, String capability, boolean grant);

    /** Removes an individual capability or role key; empty when the user is not a member. */
    Optional<Membership> removeCapability(SiteId site, UserId user, String capability);

    /**
     * Makes a user a network administrator (WordPress's super admin). False on a single site, for an unknown user and
     * when the user already is one. Requires {@code manage_network_options} for the current identity.
     */
    boolean grantSuperAdmin(UserId user);

    /**
     * Ends a user's network administration. False on a single site and when the user is not one. Revoking the last
     * one is allowed, as in WordPress; {@code quarkpress.security.initial-super-admins} restores listed subjects at the
     * next start. Requires {@code manage_network_options} for the current identity.
     */
    boolean revokeSuperAdmin(UserId user);

    /** The network administrators, by id; empty on a single site. */
    List<UserId> superAdmins();
}
