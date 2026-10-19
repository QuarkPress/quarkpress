package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.UserTarget;
import org.quarkpress.security.runtime.CapabilityReads;
import org.quarkpress.security.runtime.NetworkProbe;
import org.quarkpress.security.runtime.ReadsProbe;

/**
 * Capability checks in a network: the administrator bypass, the network restrictions, and answers per site and per
 * binding (on a single site every explicit site id answers for the only site).
 */
class CapabilitiesMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(
            CapabilitiesMultisiteDbTest.class, TestData.class, Identities.class, NetworkProbe.class, ReadsProbe.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Capabilities capabilities;

    @Inject
    SiteScope scope;

    @Inject
    NetworkProbe probe;

    @Inject
    ReadsProbe reads;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private UserId member(SiteId site, String roles, String caps) {
        UserId user = TestData.profile(dataSource, "member");
        TestData.member(dataSource, site, user, roles, caps);
        return user;
    }

    private SecurityIdentity identity(UserId user) {
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    @Test
    void aNetworkAdministratorPassesEverywhereUnlessTheMappingRefuses() {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, admin);
        PostFacts post = PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "publish", Optional.empty());
        assertThat(capabilities.userCan(admin, site, "edit_post", Optional.of(post)))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "manage_network", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "frobnicate", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "unfiltered_html", Optional.empty()))
                .isTrue();
        // the bypass comes before the core grants, which give site health to nobody in a network
        assertThat(capabilities.userCan(admin, site, "view_site_health_checks", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "edit_post", Optional.of(new MissingObject(999))))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "edit_post", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "unfiltered_upload", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "do_not_allow", Optional.empty()))
                .isFalse();
        assertThat(capabilities.isSuperAdmin(admin)).isTrue();
    }

    @Test
    void aSiteAdministratorIsRestricted() {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, site, admin, "[\"administrator\"]", "{}");
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]", "{}");
        assertThat(capabilities.userCan(admin, site, "manage_options", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "unfiltered_html", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "update_core", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "delete_site", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "delete_users", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "edit_user", Optional.of(new UserTarget(author))))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "promote_user", Optional.of(new UserTarget(author))))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "remove_user", Optional.of(new UserTarget(admin))))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "view_site_health_checks", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "activate_plugins", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "manage_network", Optional.empty()))
                .isFalse();
        assertThat(capabilities.isSuperAdmin(admin)).isFalse();
    }

    @Test
    void networkOptionsOpenRestrictedCapabilities() {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, site, admin, "[\"administrator\"]", "{}");
        Optional<String> addNewUsers = TestData.networkOptionValue(dataSource, "add_new_users");
        Optional<String> menuItems = TestData.networkOptionValue(dataSource, "menu_items");
        TestData.networkOption(dataSource, "add_new_users", "\"1\"");
        TestData.networkOption(dataSource, "menu_items", "{\"plugins\":\"1\"}");
        try {
            assertThat(capabilities.userCan(admin, site, "create_users", Optional.empty()))
                    .isTrue();
            assertThat(capabilities.userCan(admin, site, "activate_plugins", Optional.empty()))
                    .isTrue();
        } finally {
            TestData.restoreNetworkOption(dataSource, "add_new_users", addNewUsers);
            TestData.restoreNetworkOption(dataSource, "menu_items", menuItems);
        }
        TestData.networkOption(dataSource, "add_new_users", "\"0\"");
        try {
            assertThat(capabilities.userCan(admin, site, "create_users", Optional.empty()))
                    .isFalse();
        } finally {
            TestData.restoreNetworkOption(dataSource, "add_new_users", addNewUsers);
        }
    }

    @Test
    void anIndividualNetworkCapabilityCountsOnItsSiteOnly() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "granted");
        TestData.member(dataSource, site, user, "[\"administrator\"]", "{\"manage_network\":true}");
        assertThat(capabilities.userCan(user, site, "manage_network", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(user, other, "manage_network", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(user, site, "manage_network_users", Optional.empty()))
                .isFalse();
    }

    @Test
    void aNetworkAdministratorPassesOnTheMainSiteWhereTheyHaveNoMembership() {
        UserId admin = TestData.profile(dataSource, "anywhere");
        TestData.networkAdmin(dataSource, admin);
        assertThat(Identities.as(
                        Identities.user(admin, TestData.subject(dataSource, admin)),
                        () -> capabilities.can("manage_sites")))
                .isTrue();
    }

    @Test
    void answersFollowTheBinding() throws Exception {
        SiteId other = TestData.site(dataSource);
        UserId user = member(MAIN, "[\"editor\"]", "{}");
        TestData.member(dataSource, other, user, "[\"subscriber\"]", "{}");
        SecurityIdentity identity = identity(user);
        assertThat(Identities.as(identity, () -> capabilities.can("edit_others_posts")))
                .isTrue();
        assertThat(Identities.as(
                        identity, () -> capabilities.canForSite(identity, other, "edit_posts", Optional.empty())))
                .isFalse();
        assertThat(Identities.as(identity, () -> capabilities.canForSite(identity, other, "read", Optional.empty())))
                .isTrue();
        // the network scope answers for the main site
        assertThat(probe.inNetwork(() -> capabilities.userCan(user, MAIN, "edit_others_posts", Optional.empty())))
                .isTrue();
        assertThat(probe.inNetwork(() -> capabilities.can(identity, "edit_others_posts", Optional.empty())))
                .isTrue();
    }

    @Test
    void aScopeForAnotherSiteRunsWithoutTheIdentity() {
        SiteId other = TestData.site(dataSource);
        UserId user = member(other, "[\"editor\"]", "{}");
        SecurityIdentity identity = identity(user);
        boolean inScope = Identities.as(identity, () -> {
            try {
                return scope.call(other, () -> capabilities.can("read"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inScope).isFalse();
        assertThat(Identities.as(identity, () -> capabilities.canForSite(identity, other, "read", Optional.empty())))
                .isTrue();
    }

    @Test
    void theSiteRoleTableDecides() {
        SiteId other = TestData.site(dataSource);
        UserId editor = member(other, "[\"editor\"]", "{}");
        TestData.siteOption(
                dataSource,
                other,
                "qp_user_roles",
                "{\"editor\":{\"name\":\"Editor\",\"capabilities\":{\"read\":true,\"manage_options\":true}}}");
        assertThat(capabilities.userCan(editor, other, "manage_options", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, other, "edit_posts", Optional.empty()))
                .isFalse();
        // the same user on the main site, whose table is the default one
        TestData.member(dataSource, MAIN, editor, "[\"editor\"]", "{}");
        assertThat(capabilities.userCan(editor, MAIN, "manage_options", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(editor, MAIN, "edit_posts", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, other, "edit_posts", Optional.empty()))
                .isFalse();
    }

    @Test
    void aMalformedRoleTableGrantsNoRole() {
        SiteId other = TestData.site(dataSource);
        UserId editor = member(other, "[\"editor\"]", "{}");
        TestData.siteOption(dataSource, other, "qp_user_roles", "\"not a table\"");
        assertThat(capabilities.userCan(editor, other, "edit_posts", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(editor, other, "editor", Optional.empty()))
                .isTrue();
    }

    @Test
    void theLinkManagerOptionGatesManageLinks() {
        SiteId other = TestData.site(dataSource);
        UserId editor = member(other, "[\"editor\"]", "{}");
        assertThat(capabilities.userCan(editor, other, "manage_links", Optional.empty()))
                .isFalse();
        TestData.siteOption(dataSource, other, "link_manager_enabled", "1");
        assertThat(capabilities.userCan(editor, other, "manage_links", Optional.empty()))
                .isTrue();
    }

    @Test
    void aUsersMembershipsAreReadAcrossSitesInSiteOrder() {
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        UserId user = TestData.profile(dataSource, "everywhere");
        TestData.member(dataSource, second, user, "[\"subscriber\"]", "{}");
        TestData.member(
                dataSource, first, user, "[\"editor\",\"author\"]", "{\"upload_files\":0,\"manage_links\":\"1\"}");
        List<CapabilityReads.StoredMembership> found = reads.memberships(user);
        assertThat(found).extracting(CapabilityReads.StoredMembership::site).containsExactly(first, second);
        assertThat(found.get(0).roles()).containsExactly("editor", "author");
        // stored key order kept, values read as PHP reads truth
        assertThat(found.get(0).capabilities())
                .containsExactly(Map.entry("upload_files", false), Map.entry("manage_links", true));
        assertThat(found.get(1).roles()).containsExactly("subscriber");
        assertThat(found).allSatisfy(membership -> assertThat(membership.user()).isEqualTo(user));
        CapabilityReads.StoredMembership direct = reads.membership(first, user).orElseThrow();
        assertThat(direct.roles()).containsExactly("editor", "author");
        // both reads give the instant the database stored, whatever the JVM's time zone
        Instant stored = TestData.membershipCreatedAt(dataSource, first, user);
        assertThat(direct.createdAt()).isEqualTo(stored);
        assertThat(found.get(0).createdAt()).isEqualTo(stored);
    }
}
