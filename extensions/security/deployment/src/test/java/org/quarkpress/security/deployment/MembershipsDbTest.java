package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Membership;
import org.quarkpress.security.MembershipChanged;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserProfiles;

class MembershipsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(MembershipsDbTest.class, TestData.class, Identities.class, Changes.class);

    @ApplicationScoped
    public static class Changes {
        final List<MembershipChanged> seen = new CopyOnWriteArrayList<>();

        void on(@Observes MembershipChanged event) {
            seen.add(event);
        }

        List<MembershipChanged> seen() {
            return seen;
        }
    }

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    UserProfiles profiles;

    @Inject
    SystemIdentity system;

    @Inject
    Changes changes;

    @Inject
    SiteScope scope;

    @Inject
    SiteRoles siteRoles;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity administratorOf(SiteId site) {
        UserId admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, site, admin, "[\"administrator\"]", "{}");
        return Identities.user(admin, TestData.subject(dataSource, admin));
    }

    private MembershipChanged lastChange() {
        return changes.seen().get(changes.seen().size() - 1);
    }

    @Test
    void addingAUserSetsTheRoleAndThePrimarySite() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        UserId user = TestData.profile(dataSource, "new");
        Membership added = Identities.as(admin, () -> memberships.add(site, user, "author"));
        assertThat(added.roles()).containsExactly("author");
        assertThat(profiles.find(user).orElseThrow().primarySite()).contains(site);
        assertThat(lastChange().site()).isEqualTo(site);
        assertThat(lastChange().before()).isEmpty();
        assertThat(lastChange().after()).containsExactly("author");
        assertThat(capabilities.userCan(user, site, "publish_posts", Optional.empty()))
                .isTrue();
    }

    @Test
    void addingAgainReplacesTheRolesAndKeepsIndividualCapabilities() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        UserId user = TestData.profile(dataSource, "again");
        TestData.member(dataSource, site, user, "[\"editor\",\"author\"]", "{\"upload_files\":false}");
        int before = changes.seen().size();
        Membership again = Identities.as(admin, () -> memberships.add(site, user, "subscriber"));
        assertThat(again.roles()).containsExactly("subscriber");
        assertThat(again.capabilities()).containsExactly(Map.entry("upload_files", false));
        assertThat(changes.seen()).hasSize(before + 1);
        assertThat(lastChange().before()).containsExactly("editor", "author");
        Membership noRole = Identities.as(admin, () -> memberships.add(site, user, ""));
        assertThat(noRole.roles()).isEmpty();
        assertThat(memberships.find(site, user)).isPresent();
    }

    @Test
    void unknownUsersAndSitesAreRefused() {
        SiteId site = TestData.site(dataSource);
        assertThatThrownBy(() -> system.call(() -> memberships.add(site, UserId.of(999_999_999L), "author")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user 999999999 does not exist");
        UserId user = TestData.profile(dataSource, "user");
        assertThatThrownBy(() -> system.call(() -> memberships.add(SiteId.of(999_999_999L), user, "author")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("site 999999999 does not exist");
    }

    @Test
    void removingMovesThePrimarySiteAndEndsTheMembership() throws Exception {
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "leaving");
        system.call(() -> memberships.add(first, user, "author"));
        system.call(() -> memberships.add(second, user, "editor"));
        assertThat(profiles.find(user).orElseThrow().primarySite()).contains(first);
        assertThat(system.call(() -> memberships.remove(first, user))).isTrue();
        assertThat(profiles.find(user).orElseThrow().primarySite()).contains(second);
        assertThat(lastChange().after()).isEmpty();
        assertThat(system.call(() -> memberships.remove(second, user))).isTrue();
        assertThat(profiles.find(user).orElseThrow().primarySite()).isEmpty();
        assertThat(system.call(() -> memberships.remove(second, user))).isFalse();
        assertThat(memberships.sitesOf(user)).isEmpty();
    }

    @Test
    void setRoleChangesNothingWhenTheOnlyRoleIsTheSame() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "same");
        system.call(() -> memberships.add(site, user, "editor"));
        int before = changes.seen().size();
        system.call(() -> memberships.setRole(site, user, "editor"));
        assertThat(changes.seen()).hasSize(before);
        system.call(() -> memberships.addRole(site, user, "author"));
        Membership single = system.call(() -> memberships.setRole(site, user, "editor"));
        assertThat(single.roles()).containsExactly("editor");
        assertThat(lastChange().before()).containsExactly("editor", "author");
        assertThat(lastChange().after()).containsExactly("editor");
    }

    @Test
    void rolesAndCapabilitiesFollowTheUserApi() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "api");
        system.call(() -> memberships.addRole(site, user, "author"));
        assertThat(memberships.find(site, user).orElseThrow().roles()).containsExactly("author");
        assertThat(system.call(() -> memberships.addRole(site, user, "author")).roles())
                .containsExactly("author");
        Membership denied = system.call(() -> memberships.setCapability(site, user, "author", false));
        assertThat(denied.roles()).isEmpty();
        assertThat(denied.capabilities()).containsExactly(Map.entry("author", false));
        assertThat(capabilities.userCan(user, site, "publish_posts", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(user, site, "author", Optional.empty())).isFalse();
        Membership granted = system.call(() -> memberships.setCapability(site, user, "upload_files", false));
        assertThat(granted.capabilities()).containsEntry("upload_files", false);
        assertThat(system.call(() -> memberships.removeCapability(site, user, "author"))
                        .orElseThrow()
                        .capabilities())
                .doesNotContainKey("author");
        assertThat(capabilities.userCan(user, site, "publish_posts", Optional.empty()))
                .isFalse();
        assertThat(system.call(() -> memberships.removeRole(site, user, "editor"))
                        .orElseThrow()
                        .roles())
                .isEmpty();
        UserId stranger = TestData.profile(dataSource, "stranger");
        assertThat(system.call(() -> memberships.removeRole(site, stranger, "editor")))
                .isEmpty();
        assertThat(system.call(() -> memberships.removeCapability(site, stranger, "read")))
                .isEmpty();
        assertThat(memberships.find(site, stranger)).isEmpty();
    }

    @Test
    void changesNeedPromoteUserOnTheTargetSite() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]", "{}");
        UserId target = TestData.profile(dataSource, "target");
        assertThatThrownBy(() -> Identities.as(
                        Identities.user(author, TestData.subject(dataSource, author)),
                        () -> memberships.add(site, target, "editor")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability promote_user on site " + site.value());
        assertThatThrownBy(() -> Identities.as(admin, () -> memberships.add(other, target, "editor")))
                .isInstanceOf(ForbiddenException.class);
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThatThrownBy(() -> Identities.as(anonymous, () -> memberships.remove(site, author)))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(memberships.find(other, target)).isEmpty();
        assertThat(Identities.as(admin, () -> memberships.remove(site, author))).isTrue();
    }

    @Test
    void concurrentChangesOfOneMembershipAreAllKept() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "busy");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String capability = "custom_" + i;
            tasks.add(() -> system.call(() -> memberships.setCapability(site, user, capability, true)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).allMatch(Membership.class::isInstance);
        assertThat(memberships.find(site, user).orElseThrow().capabilities())
                .containsKeys("custom_0", "custom_1", "custom_2", "custom_3", "custom_4", "custom_5");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_site_membership where site_id = ? and user_id = ?",
                        site.value(),
                        user.value()))
                .isEqualTo(1);
    }

    @Test
    void aCheckInTheTransactionOfAChangeSeesTheChange() {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "same-transaction");
        List<Object> seen = new ArrayList<>();
        assertThatThrownBy(() -> scope.call(
                        site,
                        () -> system.call(() -> {
                            memberships.setRole(site, user, "editor");
                            seen.add(capabilities.userCan(user, site, "edit_others_posts", Optional.empty()));
                            seen.add(memberships.find(site, user).map(Membership::roles));
                            seen.add(memberships.sitesOf(user));
                            throw new IllegalStateException("roll back");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("roll back");
        // the check and find join the change's transaction; sitesOf reads committed rows only
        assertThat(seen).containsExactly(true, Optional.of(List.of("editor")), List.of());
        assertThat(memberships.find(site, user)).isEmpty();
        assertThat(capabilities.userCan(user, site, "edit_others_posts", Optional.empty()))
                .isFalse();
    }

    @Test
    void concurrentAddsOfOneUserToSeveralSitesAreAllKeptWithOnePrimarySite() throws Exception {
        List<SiteId> sites = List.of(TestData.site(dataSource), TestData.site(dataSource), TestData.site(dataSource));
        UserId user = TestData.profile(dataSource, "joining");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (SiteId site : sites) {
            tasks.add(() -> system.call(() -> memberships.add(site, user, "author")));
            tasks.add(() -> system.call(() -> memberships.add(site, user, "editor")));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).allMatch(Membership.class::isInstance);
        assertThat(memberships.sitesOf(user)).extracting(Membership::site).containsExactlyElementsOf(sites);
        assertThat(profiles.find(user).orElseThrow().primarySite()).get().isIn(sites);
    }

    @Test
    void removingMovesThePrimarySiteToASiteThatIsNotArchivedSpamOrDeleted() throws Exception {
        SiteId first = TestData.site(dataSource);
        SiteId archived = TestData.site(dataSource);
        SiteId third = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "moving");
        for (SiteId site : List.of(first, archived, third)) {
            system.call(() -> memberships.add(site, user, "author"));
        }
        Jdbc.update(dataSource, "update qp_site set archived = true where id = ?", archived.value());
        assertThat(system.call(() -> memberships.remove(first, user))).isTrue();
        assertThat(profiles.find(user).orElseThrow().primarySite()).contains(third);
        assertThat(system.call(() -> memberships.remove(third, user))).isTrue();
        // only an archived site is left: the user has no site, so no primary site
        assertThat(profiles.find(user).orElseThrow().primarySite()).isEmpty();
        assertThat(memberships.sitesOf(user)).extracting(Membership::site).containsExactly(archived);
    }

    @Test
    void removingTheOnlySiteWhileJoiningAnotherLeavesTheNewSitePrimary() throws Exception {
        for (int round = 0; round < 8; round++) {
            SiteId leaving = TestData.site(dataSource);
            SiteId joining = TestData.site(dataSource);
            UserId user = TestData.profile(dataSource, "switching");
            system.call(() -> memberships.add(leaving, user, "author"));
            List<Object> results = Races.run(List.of(
                    () -> system.call(() -> memberships.remove(leaving, user)),
                    () -> system.call(() -> memberships.add(joining, user, "author"))));
            assertThat(results.get(0)).isEqualTo(true);
            assertThat(results.get(1)).isInstanceOf(Membership.class);
            assertThat(profiles.find(user).orElseThrow().primarySite())
                    .as("round " + round)
                    .contains(joining);
        }
    }

    @Test
    void aSiteAdministratorCannotGiveNetworkCapabilitiesOrRolesThatDoNotExist() {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, site, admin, "[\"administrator\"]", "{}");
        SecurityIdentity identity = Identities.user(admin, TestData.subject(dataSource, admin));
        UserId other = TestData.profile(dataSource, "other");
        for (UserId target : List.of(admin, other)) {
            assertThatThrownBy(() -> Identities.as(
                            identity, () -> memberships.setCapability(site, target, "manage_network_options", true)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(
                            "the caller may not give the capability manage_network_options on site " + site.value());
            assertThatThrownBy(() ->
                            Identities.as(identity, () -> memberships.add(site, target, "manage_network_options")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("role 'manage_network_options' does not exist on site " + site.value());
            assertThatThrownBy(() -> Identities.as(identity, () -> memberships.addRole(site, target, "manage_network")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("role 'manage_network' does not exist on site " + site.value());
            assertThatThrownBy(() -> Identities.as(identity, () -> memberships.setRole(site, target, "super_admin")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("role 'super_admin' does not exist on site " + site.value());
        }
        // nor through the site's role table
        assertThatThrownBy(() -> Identities.as(
                        identity, () -> siteRoles.setCapability(site, "administrator", "manage_network_options", true)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller may not give the capability manage_network_options on site " + site.value());
        assertThatThrownBy(() -> Identities.as(
                        identity, () -> siteRoles.add(site, "netops", "Net ops", Map.of("create_sites", true))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller may not give the capability create_sites on site " + site.value());
        assertThat(memberships.find(site, admin).orElseThrow().capabilities()).isEmpty();
        assertThat(memberships.find(site, other)).isEmpty();
        assertThat(siteRoles.role(site, "netops")).isEmpty();
        assertThat(capabilities.canForSite(identity, site, "manage_network_options", Optional.empty()))
                .isFalse();
        // what they hold they may give: the administrator role, and a role or capability within it
        assertThat(Identities.as(identity, () -> memberships.add(site, other, "administrator"))
                        .roles())
                .containsExactly("administrator");
        assertThat(Identities.as(identity, () -> memberships.setCapability(site, other, "edit_users", true))
                        .capabilities())
                .containsEntry("edit_users", true);
    }

    @Test
    void aCallerMayGiveOnlyRolesAndCapabilitiesTheyHold() throws Exception {
        SiteId site = TestData.site(dataSource);
        system.call(() -> siteRoles.add(
                site,
                "manager",
                "Manager",
                Map.of(
                        "read", true,
                        "edit_posts", true,
                        "delete_posts", true,
                        "level_1", true,
                        "level_0", true,
                        "promote_users", true)));
        UserId manager = TestData.profile(dataSource, "manager");
        TestData.member(dataSource, site, manager, "[\"manager\"]", "{}");
        SecurityIdentity identity = Identities.user(manager, TestData.subject(dataSource, manager));
        UserId target = TestData.profile(dataSource, "managed");
        assertThat(Identities.as(identity, () -> memberships.add(site, target, "contributor"))
                        .roles())
                .containsExactly("contributor");
        assertThatThrownBy(() -> Identities.as(identity, () -> memberships.setRole(site, target, "editor")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageStartingWith("the caller may not give the capability ")
                .hasMessageEndingWith(" on site " + site.value());
        assertThatThrownBy(() -> Identities.as(identity, () -> memberships.addRole(site, manager, "administrator")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.as(
                        identity, () -> memberships.setCapability(site, target, "edit_others_posts", true)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller may not give the capability edit_others_posts on site " + site.value());
        assertThatThrownBy(() -> Identities.as(identity, () -> memberships.setCapability(site, target, "editor", true)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Identities.as(identity, () -> memberships.setCapability(site, target, "edit_posts", false))
                        .capabilities())
                .containsEntry("edit_posts", false);
        assertThat(memberships.find(site, target).orElseThrow().roles()).containsExactly("contributor");
        assertThat(memberships.find(site, manager).orElseThrow().roles()).containsExactly("manager");
    }

    @Test
    void storingARoleKeyAsADenialStillGivesTheRoleAndIsCheckedAsSuch() throws Exception {
        SiteId site = TestData.site(dataSource);
        system.call(() -> siteRoles.add(
                site,
                "manager",
                "Manager",
                Map.of(
                        "read", true,
                        "edit_posts", true,
                        "delete_posts", true,
                        "level_1", true,
                        "level_0", true,
                        "promote_users", true)));
        UserId manager = TestData.profile(dataSource, "denying-manager");
        TestData.member(dataSource, site, manager, "[\"manager\"]", "{}");
        SecurityIdentity identity = Identities.user(manager, TestData.subject(dataSource, manager));
        UserId target = TestData.profile(dataSource, "denied-target");
        TestData.member(dataSource, site, target, "[\"contributor\"]", "{}");
        for (UserId user : List.of(manager, target)) {
            assertThatThrownBy(() -> Identities.as(
                            identity, () -> memberships.setCapability(site, user, "administrator", false)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageStartingWith("the caller may not give the capability ")
                    .hasMessageEndingWith(" on site " + site.value());
            assertThat(memberships.find(site, user).orElseThrow().capabilities())
                    .isEmpty();
        }
        assertThat(capabilities.userCan(manager, site, "manage_options", Optional.empty()))
                .isFalse();
        // denying a key that is not a role stays allowed
        assertThat(Identities.as(identity, () -> memberships.setCapability(site, target, "edit_posts", false))
                        .capabilities())
                .containsExactly(Map.entry("edit_posts", false));
    }

    @Test
    void aRoleKeyInsideARoleIsAPlainCapability() throws Exception {
        // so a role table has no path to give another role: only a membership's keys expand into roles
        SiteId site = TestData.site(dataSource);
        assertThat(system.call(() -> siteRoles.setCapability(site, "subscriber", "administrator", true)))
                .isTrue();
        UserId user = TestData.profile(dataSource, "subscribed");
        TestData.member(dataSource, site, user, "[\"subscriber\"]", "{}");
        assertThat(capabilities.userCan(user, site, "administrator", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(user, site, "manage_options", Optional.empty()))
                .isFalse();
        assertThat(memberships.find(site, user).orElseThrow().roles()).containsExactly("subscriber");
    }

    @Test
    void aNetworkAdministratorMayGiveAnyRoleOrCapability() {
        SiteId site = TestData.site(dataSource);
        UserId networkAdmin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, networkAdmin);
        SecurityIdentity identity = Identities.user(networkAdmin, TestData.subject(dataSource, networkAdmin));
        UserId user = TestData.profile(dataSource, "promoted");
        assertThat(Identities.as(identity, () -> memberships.add(site, user, "administrator"))
                        .roles())
                .containsExactly("administrator");
        assertThat(Identities.as(identity, () -> memberships.setCapability(site, user, "manage_network_options", true))
                        .capabilities())
                .containsEntry("manage_network_options", true);
        assertThat(Identities.as(identity, () -> siteRoles.setCapability(site, "editor", "manage_network_users", true)))
                .isTrue();
        // stored, but only the network administrator bypass satisfies a network capability
        assertThat(capabilities.userCan(user, site, "manage_network_options", Optional.empty()))
                .isFalse();
    }

    @Test
    void changesOfOneUserOnTwoSitesInOneTransactionFailFast() {
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "two-sites");
        long started = System.nanoTime();
        assertThatThrownBy(() -> scope.call(
                        first,
                        () -> system.call(() -> {
                            memberships.add(first, user, "author");
                            return memberships.add(second, user, "author");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + user.value()
                        + " is locked by a transaction that waits for this change: change the memberships of one"
                        + " user on several sites in separate transactions");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(memberships.sitesOf(user)).isEmpty();
    }

    @Test
    void changesThatCreateAMembershipAfterAChangeOnAnotherSiteFailFast() {
        // setRole, addRole and setCapability of a non-member create the membership, as add does
        List<String> refused = new ArrayList<>();
        for (String operation : List.of("setRole", "addRole", "setCapability")) {
            SiteId first = TestData.site(dataSource);
            SiteId second = TestData.site(dataSource);
            UserId user = TestData.profile(dataSource, "two-sites-" + operation);
            long started = System.nanoTime();
            assertThatThrownBy(() -> scope.call(
                            first,
                            () -> system.call(() -> {
                                memberships.add(first, user, "author");
                                return switch (operation) {
                                    case "setRole" -> memberships.setRole(second, user, "editor");
                                    case "addRole" -> memberships.addRole(second, user, "editor");
                                    default -> memberships.setCapability(second, user, "upload_files", true);
                                };
                            })))
                    .as(operation)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the profile of user " + user.value()
                            + " is locked by a transaction that waits for this change: change the memberships of one"
                            + " user on several sites in separate transactions");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as(operation)
                    .isLessThan(Duration.ofSeconds(10));
            assertThat(memberships.sitesOf(user)).as(operation).isEmpty();
            refused.add(operation);
        }
        assertThat(refused).containsExactly("setRole", "addRole", "setCapability");
    }

    @Test
    void aRemovalRecordsTheProfileLockSoAChangeOnAnotherSiteFailsFast() {
        // remove takes the profile lock only through the shared lock path, which must record it
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "remove-then-add");
        TestData.member(dataSource, first, user, "[\"author\"]", "{}");
        String lock = "qp_user_profile:" + user.value();
        long started = System.nanoTime();
        assertThatThrownBy(() -> scope.call(
                        first,
                        () -> system.call(() -> {
                            assertThat(memberships.remove(first, user)).isTrue();
                            // checked before the change, which would otherwise wait for the row until a timeout
                            assertThat(scope.call(second, () -> HeldLocks.heldBySpawner(lock)))
                                    .as("a scope this transaction spawns sees the profile lock")
                                    .isTrue();
                            return memberships.add(second, user, "author");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + user.value()
                        + " is locked by a transaction that waits for this change: change the memberships of one"
                        + " user on several sites in separate transactions");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(memberships.sitesOf(user)).extracting(Membership::site).containsExactly(first);
    }

    @Test
    void aChangeOfAMembershipThatExistsAfterAChangeOnAnotherSiteIsMade() throws Exception {
        // no membership is created, so nothing waits for the profile
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "two-sites-member");
        TestData.member(dataSource, second, user, "[\"author\"]", "{}");
        long started = System.nanoTime();
        Membership changed = scope.call(
                first,
                () -> system.call(() -> {
                    memberships.add(first, user, "author");
                    memberships.setCapability(second, user, "upload_files", false);
                    return memberships.setRole(second, user, "editor");
                }));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(changed.roles()).containsExactly("editor");
        assertThat(changed.capabilities()).containsExactly(Map.entry("upload_files", false));
        assertThat(memberships.find(first, user).orElseThrow().roles()).containsExactly("author");
    }

    @Test
    void addingAnEmptyRoleWritesNothing() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "empty-role");
        assertThatThrownBy(() -> system.call(() -> memberships.addRole(site, user, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a role to add must not be empty");
        assertThat(memberships.find(site, user)).isEmpty();
        assertThat(memberships.isMember(site, user)).isFalse();
    }

    @Test
    void grantingARoleKeyKeepsTheOrderOfTheRoles() throws Exception {
        SiteId site = TestData.site(dataSource);
        system.call(() -> siteRoles.add(site, "shop", "Shop", Map.of("edit_others_posts", false)));
        UserId user = TestData.profile(dataSource, "ordered");
        TestData.member(dataSource, site, user, "[\"editor\",\"shop\"]", "{}");
        assertThat(capabilities.userCan(user, site, "edit_others_posts", Optional.empty()))
                .isFalse();
        Membership granted = system.call(() -> memberships.setCapability(site, user, "editor", true));
        assertThat(granted.roles()).containsExactly("editor", "shop");
        assertThat(capabilities.userCan(user, site, "edit_others_posts", Optional.empty()))
                .isFalse();
    }
}
