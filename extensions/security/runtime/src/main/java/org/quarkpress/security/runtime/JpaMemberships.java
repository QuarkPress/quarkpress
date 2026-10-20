package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.security.Membership;
import org.quarkpress.security.MembershipChanged;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.UserTarget;
import org.quarkpress.security.runtime.CapabilityReads.StoredMembership;

/**
 * Memberships in qp_site_membership. A change runs in a transaction bound to the site; the user's profile row is locked
 * first when the change may move the user's primary site, then the membership row (created first, without a race,
 * when the change creates a membership). Changes are flushed before they return, so capability checks and
 * {@link #find} made later in the same transaction see them; {@link #sitesOf} reads committed rows only, unless the
 * caller is bound to the network.
 *
 * <p>A change that would wait for the user's profile row while a transaction that waits for it holds that row (a change
 * of the same user on another site, earlier in the caller's transaction) is refused at once with
 * {@link IllegalStateException}: any change that moves the primary site ({@code add}, {@code remove}) and any change
 * that creates a membership ({@code setRole}, {@code addRole} or {@code setCapability} of a non-member), whose insert
 * waits for the profile row on MySQL (the foreign key check); it is refused on both databases alike. Adding a user can
 * still deadlock with the deletion of the site: the add holds the profile and its insert waits for the site's row,
 * while the deletion holds the site's row and its cascade to the profile's primary site waits for the profile. The
 * database rolls one back; here that failure, like any deadlock or lock timeout, surfaces as a
 * {@link jakarta.persistence.PessimisticLockException}: retry the whole transaction.
 */
@ApplicationScoped
public class JpaMemberships implements Memberships {

    @Inject
    CapabilityReads reads;

    @Inject
    CapabilityGuard guard;

    @Inject
    SiteScope scope;

    @Inject
    MembershipInserts inserts;

    @Inject
    SiteMembershipRepository memberships;

    @Inject
    UserProfileRepository profileRows;

    @Inject
    UserProfiles profiles;

    @Inject
    SiteRoleTables tables;

    @Inject
    SiteDirectory sites;

    @Inject
    InstallMode mode;

    @Inject
    CheckSite checkSite;

    @Inject
    QuarkPressClock clock;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<MembershipChanged> changed;

    @Inject
    NetworkAdmins networkAdmins;

    @Override
    public Optional<Membership> find(SiteId site, UserId user) {
        return reads.membership(target(site), user).map(JpaMemberships::toMembership);
    }

    @Override
    public List<Membership> sitesOf(UserId user) {
        return reads.memberships(user).stream()
                .map(JpaMemberships::toMembership)
                .toList();
    }

    @Override
    public boolean isMember(SiteId site, UserId user) {
        if (profiles.find(user).isEmpty()) {
            return false;
        }
        if (!mode.multisite()) {
            return true;
        }
        return active(site) && reads.membership(site, user).isPresent();
    }

    @Override
    public Membership add(SiteId site, UserId user, String role) {
        Objects.requireNonNull(role, "role");
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        if (!role.isEmpty()) {
            requireMayGiveRole(target(site), role);
        }
        return addWithoutCheck(site, user, role);
    }

    /** {@link #add} without the capability check: for startup bootstraps. */
    public Membership addWithoutCheck(SiteId site, UserId user, String role) {
        Objects.requireNonNull(role, "role");
        SiteId target = target(site);
        requireExisting(target, user);
        return change(target, user, true, true, true, state -> {
                    state.replaceRoles(role);
                    return true;
                })
                .orElseThrow();
    }

    @Override
    public boolean remove(SiteId site, UserId user) {
        guard.require(site, "remove_user", Optional.of(new UserTarget(user)));
        SiteId target = target(site);
        return inSite(target, user, () -> {
            Optional<UserProfileEntity> profile = lockedProfile(user);
            Optional<SiteMembershipEntity> row = locked(target, user);
            if (row.isEmpty()) {
                return false;
            }
            Set<String> before = new State(row.get(), tables.byKey(target)).roles();
            memberships.delete(row.get());
            if (profile.isPresent()) {
                movePrimarySite(profile.get(), target);
            }
            memberships.flush();
            transactions.publish(changed, new MembershipChanged(target, now(), user, before, Set.of()));
            return true;
        });
    }

    @Override
    public Membership setRole(SiteId site, UserId user, String role) {
        Objects.requireNonNull(role, "role");
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        if (!role.isEmpty()) {
            requireMayGiveRole(target(site), role);
        }
        return setRoleWithoutCheck(site, user, role);
    }

    /** {@link #setRole} without the capability check: for dev seeding. */
    public Membership setRoleWithoutCheck(SiteId site, UserId user, String role) {
        Objects.requireNonNull(role, "role");
        SiteId target = target(site);
        requireExisting(target, user);
        return change(target, user, true, false, false, state -> {
                    Set<String> current = state.roles();
                    if (current.size() == 1 && current.contains(role)) {
                        return false;
                    }
                    state.replaceRoles(role);
                    return true;
                })
                .orElseThrow();
    }

    @Override
    public Membership addRole(SiteId site, UserId user, String role) {
        requireRole(role);
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        requireMayGiveRole(target(site), role);
        return addRoleWithoutCheck(site, user, role);
    }

    /** {@link #addRole} without the capability check and the role validation: for startup bootstraps. */
    public Membership addRoleWithoutCheck(SiteId site, UserId user, String role) {
        requireRole(role);
        SiteId target = target(site);
        requireExisting(target, user);
        return change(target, user, true, false, false, state -> state.addRole(role))
                .orElseThrow();
    }

    @Override
    public Optional<Membership> removeRole(SiteId site, UserId user, String role) {
        Objects.requireNonNull(role, "role");
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        return change(target(site), user, false, false, false, state -> state.removeRole(role));
    }

    @Override
    public Membership setCapability(SiteId site, UserId user, String capability, boolean grant) {
        Objects.requireNonNull(capability, "capability");
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        SiteId target = target(site);
        RoleDefinition role = tables.byKey(target).get(capability);
        // a stored role key gives the role, granted or denied (only the key itself is denied)
        guard.requireMayGive(target, role != null ? role.capabilities() : Map.of(capability, grant));
        requireExisting(target, user);
        return change(target, user, true, false, false, state -> {
                    state.setCapability(capability, grant);
                    return true;
                })
                .orElseThrow();
    }

    @Override
    public Optional<Membership> removeCapability(SiteId site, UserId user, String capability) {
        Objects.requireNonNull(capability, "capability");
        guard.require(site, "promote_user", Optional.of(new UserTarget(user)));
        return change(target(site), user, false, false, false, state -> state.removeCapability(capability));
    }

    @Override
    public boolean grantSuperAdmin(UserId user) {
        Objects.requireNonNull(user, "user");
        if (!mode.multisite()) {
            return false;
        }
        guard.requireHere("manage_network_options");
        Optional<UserId> grantor =
                QuarkPressPrincipal.of(JpaCapabilities.currentIdentity()).map(QuarkPressPrincipal::user);
        return networkAdmins.grant(user, grantor);
    }

    @Override
    public boolean revokeSuperAdmin(UserId user) {
        Objects.requireNonNull(user, "user");
        if (!mode.multisite()) {
            return false;
        }
        guard.requireHere("manage_network_options");
        return networkAdmins.revoke(user);
    }

    @Override
    public List<UserId> superAdmins() {
        return mode.multisite() ? reads.networkAdmins() : List.of();
    }

    /**
     * Changes a membership in a transaction bound to the site: the row is created first when {@code create} (race-free)
     * and locked; {@code edit} says whether it changed the row. A user without a primary site gets this one when
     * {@code primary}. The change event is fired when the user's roles changed, or always when {@code alwaysFire}.
     */
    private Optional<Membership> change(
            SiteId site, UserId user, boolean create, boolean alwaysFire, boolean primary, Predicate<State> edit) {
        return inSite(site, user, () -> {
            Instant now = now();
            // the profile before the membership, in every change that takes both locks (see remove)
            Optional<UserProfileEntity> profile = primary ? lockedProfile(user) : Optional.empty();
            if (create) {
                // on MySQL a new membership row holds a shared lock on the profile (the foreign key check), which waits
                // for a transaction that holds the profile and waits for this change
                if (!primary && !exists(site, user)) {
                    refuseIfHeldBySpawner(user);
                }
                HeldLocks.record(profileLock(user));
                inserts.ensure(site, user, now);
            }
            Optional<SiteMembershipEntity> row = locked(site, user);
            if (row.isEmpty()) {
                return Optional.<Membership>empty();
            }
            State state = new State(row.get(), tables.byKey(site));
            Set<String> before = state.roles();
            if (edit.test(state)) {
                row.get().roles = state.rolesJson();
                row.get().capabilities = state.capabilitiesJson();
            }
            if (profile.isPresent() && profile.get().primarySiteId == null) {
                profile.get().primarySiteId = site.value();
            }
            memberships.flush();
            Set<String> after = state.roles();
            if (alwaysFire || !before.equals(after)) {
                transactions.publish(changed, new MembershipChanged(site, now, user, before, after));
            }
            return Optional.of(toMembership(row.get()));
        });
    }

    private Optional<SiteMembershipEntity> locked(SiteId site, UserId user) {
        return memberships
                .find("siteId = ?1 and userId = ?2", TenantIds.param(site), user.value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional();
    }

    /**
     * The user's profile, locked for update until the transaction ends, with its state read by the locking query:
     * concurrent changes that may move the primary site apply one after the other, and on MySQL the state is not the
     * transaction's older snapshot. A profile this transaction already loaded is detached first, as a refresh would
     * read without the lock.
     */
    private Optional<UserProfileEntity> lockedProfile(UserId user) {
        String lock = profileLock(user);
        refuseIfHeldBySpawner(user);
        UserProfileEntity loaded = profileRows.findById(user.value());
        if (loaded == null) {
            return Optional.empty();
        }
        // pending changes of the caller's transaction are written first, then read back under the lock
        profileRows.flush();
        profileRows.getEntityManager().detach(loaded);
        Optional<UserProfileEntity> locked = profileRows
                .find("id = ?1", user.value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional();
        HeldLocks.record(lock);
        return locked;
    }

    /**
     * Refuses a change that would wait for the user's profile row while a transaction that waits for this change holds
     * it: the two would wait for each other until the database's lock timeout.
     */
    private static void refuseIfHeldBySpawner(UserId user) {
        if (HeldLocks.heldBySpawner(profileLock(user))) {
            throw new IllegalStateException("the profile of user " + user.value()
                    + " is locked by a transaction that waits for this change: change the memberships of one user on"
                    + " several sites in separate transactions");
        }
    }

    /** Whether the membership row exists, as this transaction sees it (no lock). */
    private boolean exists(SiteId site, UserId user) {
        return memberships.count("siteId = ?1 and userId = ?2", TenantIds.param(site), user.value()) > 0;
    }

    /** The name under which a transaction records its lock on the user's profile row ({@link HeldLocks}). */
    static String profileLock(UserId user) {
        return "qp_user_profile:" + user.value();
    }

    private static void requireRole(String role) {
        Objects.requireNonNull(role, "role");
        if (role.isEmpty()) {
            throw new IllegalArgumentException("a role to add must not be empty");
        }
    }

    /**
     * Refuses a role the site's table lacks ({@link IllegalArgumentException}) and one whose capabilities the current
     * identity may not give ({@code ForbiddenException}).
     */
    private void requireMayGiveRole(SiteId site, String role) {
        RoleDefinition found = tables.byKey(site).get(role);
        if (found == null) {
            throw new IllegalArgumentException("role '" + role + "' does not exist on site " + site.value());
        }
        guard.requireMayGive(site, found.capabilities());
    }

    /**
     * After the membership of {@code site} ended: a primary site that was {@code site} moves to the user's first other
     * site that is not archived, spam or deleted (by id), and a user left without such a site has no primary site.
     */
    private void movePrimarySite(UserProfileEntity profile, SiteId site) {
        UserId user = UserId.of(profile.id);
        // committed memberships, read on a thread of their own: the row deleted here may still be listed
        List<Long> others = reads.memberships(user).stream()
                .map(StoredMembership::site)
                .filter(other -> !other.equals(site))
                .filter(this::active)
                .map(SiteId::value)
                .toList();
        if (others.isEmpty()) {
            profile.primarySiteId = null;
        } else if (Long.valueOf(site.value()).equals(profile.primarySiteId)) {
            profile.primarySiteId = others.get(0);
        }
    }

    private boolean active(SiteId site) {
        Optional<SiteInfo> info = sites.find(site);
        return info.isPresent()
                && !info.get().archived()
                && !info.get().spam()
                && !info.get().deleted();
    }

    /**
     * The site a membership is of. On a single site WordPress has no site dimension, so the main site; in a network,
     * the site given (as capability checks answer).
     */
    private SiteId target(SiteId site) {
        Objects.requireNonNull(site, "site");
        return mode.multisite() ? site : checkSite.main();
    }

    private void requireExisting(SiteId site, UserId user) {
        if (profiles.find(user).isEmpty()) {
            throw new IllegalArgumentException("user " + user.value() + " does not exist");
        }
        if (sites.find(site).isEmpty()) {
            throw new IllegalArgumentException("site " + site.value() + " does not exist");
        }
    }

    private <T> T inSite(SiteId site, UserId user, Callable<T> work) {
        try {
            return scope.call(site, work);
        } catch (RuntimeException e) {
            throw LockConflicts.retryable(
                    e, "a membership change of user " + user.value() + " on site " + site.value());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static Membership toMembership(CapabilityReads.StoredMembership stored) {
        return new Membership(stored.site(), stored.user(), stored.roles(), stored.capabilities(), stored.createdAt());
    }

    private static Membership toMembership(SiteMembershipEntity row) {
        return new Membership(
                row.site(),
                UserId.of(row.userId),
                CapabilityReads.roles(row.roles.toString()),
                CapabilityReads.capabilities(row.capabilities.toString()),
                row.createdAt);
    }

    /** A membership's role keys and capabilities while it is changed; a key lives in only one of the two. */
    private static final class State {

        private final List<String> roleKeys;
        private final Map<String, Boolean> capabilities;
        private final Map<String, RoleDefinition> siteRoles;

        State(SiteMembershipEntity row, Map<String, RoleDefinition> siteRoles) {
            this.roleKeys = new ArrayList<>(CapabilityReads.roles(row.roles.toString()));
            this.capabilities = new LinkedHashMap<>(CapabilityReads.capabilities(row.capabilities.toString()));
            this.siteRoles = siteRoles;
        }

        /** The user's roles on the site. */
        Set<String> roles() {
            return new LinkedHashSet<>(AllCaps.roles(siteRoles, roleKeys, capabilities));
        }

        /** Removes every role key the site's table has; then adds {@code role} unless it is empty. */
        void replaceRoles(String role) {
            roleKeys.removeIf(siteRoles::containsKey);
            capabilities.keySet().removeIf(siteRoles::containsKey);
            if (!role.isEmpty()) {
                roleKeys.remove(role);
                capabilities.remove(role);
                roleKeys.add(role);
            }
        }

        boolean addRole(String role) {
            if (role.isEmpty() || roles().contains(role) || roleKeys.contains(role)) {
                return false;
            }
            capabilities.remove(role);
            roleKeys.add(role);
            return true;
        }

        boolean removeRole(String role) {
            if (!roles().contains(role)) {
                return false;
            }
            roleKeys.remove(role);
            capabilities.remove(role);
            return true;
        }

        /**
         * Grants or denies a name in place, as WordPress overwrites the entry: granting a listed role key keeps it
         * where it is, so the order of the roles stays; a denial of a listed role key moves it to the capabilities,
         * after the listed roles.
         */
        void setCapability(String capability, boolean grant) {
            if (grant && roleKeys.contains(capability)) {
                capabilities.remove(capability);
                return;
            }
            roleKeys.remove(capability);
            capabilities.put(capability, grant);
        }

        boolean removeCapability(String capability) {
            boolean listed = roleKeys.remove(capability);
            return capabilities.remove(capability) != null || listed;
        }

        JsonNode rolesJson() {
            ArrayNode json = JsonNodeFactory.instance.arrayNode();
            roleKeys.forEach(json::add);
            return json;
        }

        JsonNode capabilitiesJson() {
            ObjectNode json = JsonNodeFactory.instance.objectNode();
            capabilities.forEach(json::put);
            return json;
        }
    }
}
