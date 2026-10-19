package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SuperAdminChanged;
import org.quarkpress.security.SystemIdentity;

class SuperAdminsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(SuperAdminsDbTest.class, TestData.class, Identities.class, Changes.class);

    @ApplicationScoped
    public static class Changes {
        final List<SuperAdminChanged> seen = new CopyOnWriteArrayList<>();

        void on(@Observes SuperAdminChanged event) {
            seen.add(event);
        }

        List<SuperAdminChanged> seen() {
            return seen;
        }
    }

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    Changes changes;

    @Inject
    SiteScope scope;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity identity(UserId user) {
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    @Test
    void aNetworkAdministratorGrantsAndRevokesAndTheLastOneMayGo() {
        UserId alice = TestData.profile(dataSource, "alice");
        TestData.networkAdmin(dataSource, alice);
        UserId bob = TestData.profile(dataSource, "bob");
        SiteId site = TestData.site(dataSource);
        int before = changes.seen().size();
        assertThat(Identities.as(identity(alice), () -> memberships.grantSuperAdmin(bob)))
                .isTrue();
        assertThat(capabilities.userCan(bob, site, "manage_network", Optional.empty()))
                .isTrue();
        assertThat(capabilities.isSuperAdmin(bob)).isTrue();
        assertThat(Jdbc.count(dataSource, "select granted_by from qp_network_admin where user_id = ?", bob.value()))
                .isEqualTo(alice.value());
        assertThat(Identities.as(identity(alice), () -> memberships.grantSuperAdmin(bob)))
                .isFalse();
        assertThat(changes.seen()).hasSize(before + 1);
        assertThat(changes.seen().get(before).granted()).isTrue();
        assertThat(memberships.superAdmins()).contains(alice, bob);
        assertThat(Identities.as(identity(alice), () -> memberships.revokeSuperAdmin(bob)))
                .isTrue();
        assertThat(Identities.as(identity(alice), () -> memberships.revokeSuperAdmin(bob)))
                .isFalse();
        assertThat(capabilities.userCan(bob, site, "manage_network", Optional.empty()))
                .isFalse();
        assertThat(Identities.as(identity(alice), () -> memberships.grantSuperAdmin(UserId.of(999_999_999L))))
                .isFalse();
        assertThat(Identities.as(identity(alice), () -> memberships.revokeSuperAdmin(alice)))
                .isTrue();
        assertThat(memberships.superAdmins()).doesNotContain(alice, bob);
    }

    @Test
    void grantingNeedsManageNetworkOptions() {
        UserId siteAdmin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, SiteId.of(1), siteAdmin, "[\"administrator\"]", "{}");
        UserId target = TestData.profile(dataSource, "target");
        assertThatThrownBy(() -> Identities.as(identity(siteAdmin), () -> memberships.grantSuperAdmin(target)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_network_options for this request");
        assertThatThrownBy(() -> Identities.as(identity(siteAdmin), () -> memberships.revokeSuperAdmin(target)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_network_options for this request");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", target.value()))
                .isZero();
    }

    /**
     * Ruling T16c: in a network, only a network administrator or the system holds {@code manage_network_options}; a
     * stored grant of it on a site membership counts for nothing, so it cannot be used to become a network
     * administrator.
     */
    @Test
    void aStoredManageNetworkOptionsDoesNotLetASiteAdministratorGrant() {
        UserId holder = TestData.profile(dataSource, "options-holder");
        TestData.member(dataSource, SiteId.of(1), holder, "[\"administrator\"]", "{\"manage_network_options\":true}");
        assertThatThrownBy(() -> Identities.as(identity(holder), () -> memberships.grantSuperAdmin(holder)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_network_options for this request");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", holder.value()))
                .isZero();
        assertThat(capabilities.isSuperAdmin(holder)).isFalse();
    }

    @Test
    void concurrentGrantsOfOneUserGrantOnce() throws Exception {
        UserId target = TestData.profile(dataSource, "racer");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> system.call(() -> memberships.grantSuperAdmin(target)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results.stream().filter(Boolean.TRUE::equals).count()).isEqualTo(1);
        assertThat(results).allMatch(result -> result instanceof Boolean);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", target.value()))
                .isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_network_admin where user_id = ? and granted_by is null",
                        target.value()))
                .isEqualTo(1);
    }

    /**
     * A grant locks the network lock, then the user's profile (shared); a membership change locks the profile, then
     * the membership. Racing both for one user must let every call complete, with one grant.
     */
    @Test
    void grantsRaceMembershipChangesOfTheSameUser() throws Exception {
        UserId user = TestData.profile(dataSource, "grant-vs-membership");
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> system.call(() -> memberships.grantSuperAdmin(user)));
            tasks.add(() -> system.call(() -> memberships.add(first, user, "author")));
            tasks.add(() -> system.call(() -> memberships.remove(first, user)));
            tasks.add(() -> system.call(() -> memberships.add(second, user, "editor")));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).noneMatch(result -> result instanceof Throwable);
        long grants = 0;
        for (int i = 0; i < results.size(); i += 4) {
            if (Boolean.TRUE.equals(results.get(i))) {
                grants++;
            }
        }
        assertThat(grants).isEqualTo(1);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", user.value()))
                .isEqualTo(1);
        assertThat(memberships.find(second, user).orElseThrow().roles()).containsExactly("editor");
    }

    /** Grants and revokes of one user apply one after the other: the row and the events agree. */
    @Test
    void grantsRaceRevokesOfTheSameUser() throws Exception {
        UserId user = TestData.profile(dataSource, "grant-vs-revoke");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> system.call(() -> memberships.grantSuperAdmin(user)));
            tasks.add(() -> system.call(() -> memberships.revokeSuperAdmin(user)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).allMatch(result -> result instanceof Boolean);
        long granted = 0;
        long revoked = 0;
        for (int i = 0; i < results.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                if (i % 2 == 0) {
                    granted++;
                } else {
                    revoked++;
                }
            }
        }
        long rows = Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", user.value());
        assertThat(granted - revoked).isEqualTo(rows).isBetween(0L, 1L);
        List<SuperAdminChanged> events = changes.seen().stream()
                .filter(event -> event.user().equals(user))
                .toList();
        assertThat(events.stream().filter(SuperAdminChanged::granted).count()).isEqualTo(granted);
        assertThat(events.stream().filter(event -> !event.granted()).count()).isEqualTo(revoked);
        // they alternate: grant, revoke, grant, ...
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).granted()).isEqualTo(i % 2 == 0);
        }
    }

    /**
     * A grant on a scope thread whose spawner's transaction holds the user's profile lock (it changed the user's
     * membership) would wait for that lock until the lock timeout: it fails at once instead.
     */
    @Test
    void aGrantInsideAScopeWhoseSpawnerHoldsTheProfileFailsFast() {
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "grant-held");
        long started = System.nanoTime();
        assertThatThrownBy(() -> scope.call(
                        first,
                        () -> system.call(() -> {
                            memberships.add(first, user, "author");
                            return scope.call(second, () -> system.call(() -> memberships.grantSuperAdmin(user)));
                        })))
                .satisfies(failure -> assertThat(Failures.messages(failure))
                        .contains("java.lang.IllegalStateException: the profile of user " + user.value()
                                + " is locked by a transaction that waits for this grant: grant in a separate"
                                + " transaction"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", user.value()))
                .isZero();
        assertThat(memberships.sitesOf(user)).isEmpty();
    }
}
