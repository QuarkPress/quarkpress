package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.UserTarget;

/** Capability checks on a single site (no network administrator bypass). */
class CapabilitiesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.with(CapabilitiesDbTest.class, TestData.class, Identities.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Capabilities capabilities;

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
    void anEditorHoldsEditorCapabilitiesAndTheRoleKey() {
        UserId editor = member(MAIN, "[\"editor\"]", "{}");
        assertThat(capabilities.userCan(editor, MAIN, "edit_others_posts", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, MAIN, "unfiltered_html", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, MAIN, "edit_css", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, MAIN, "editor", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, MAIN, "manage_options", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(editor, MAIN, "manage_links", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(editor, MAIN, "frobnicate", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(editor, MAIN, "exist", Optional.empty()))
                .isTrue();
    }

    @Test
    void perUserEntriesWinOverRoles() {
        UserId user = member(MAIN, "[\"editor\"]", "{\"edit_others_posts\":false,\"manage_options\":true}");
        assertThat(capabilities.userCan(user, MAIN, "edit_others_posts", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(user, MAIN, "manage_options", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(user, MAIN, "edit_posts", Optional.empty()))
                .isTrue();
    }

    @Test
    void doNotAllowIsNeverHeldAndExistAlwaysIs() {
        UserId user = member(MAIN, "[]", "{\"do_not_allow\":true}");
        assertThat(capabilities.userCan(user, MAIN, "do_not_allow", Optional.empty()))
                .isFalse();
        assertThat(capabilities.anonymousCan(MAIN, "exist", Optional.empty())).isTrue();
        assertThat(capabilities.anonymousCan(MAIN, "read", Optional.empty())).isFalse();
        PostFacts published = PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "publish", Optional.of(user));
        assertThat(capabilities.anonymousCan(MAIN, "read_post", Optional.of(published)))
                .isFalse();
        assertThat(capabilities.anonymousCan(MAIN, "edit_user", Optional.of(new UserTarget(user))))
                .isFalse();
    }

    @Test
    void aUserWithoutMembershipHoldsOnlyExistAndEditsThemselves() {
        UserId stranger = TestData.profile(dataSource, "stranger");
        assertThat(capabilities.userCan(stranger, MAIN, "read", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(stranger, MAIN, "exist", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(stranger, MAIN, "edit_user", Optional.of(new UserTarget(stranger))))
                .isTrue();
    }

    @Test
    void aNetworkAdminRowGrantsNothingOnASingleSite() {
        UserId user = member(MAIN, "[\"subscriber\"]", "{}");
        TestData.networkAdmin(dataSource, user);
        assertThat(capabilities.userCan(user, MAIN, "manage_network", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(user, MAIN, "edit_posts", Optional.empty()))
                .isFalse();
        assertThat(capabilities.isSuperAdmin(user)).isFalse();
    }

    @Test
    void onASingleSiteASuperAdminIsWhoeverCanDeleteUsers() {
        UserId admin = member(MAIN, "[\"administrator\"]", "{}");
        UserId editor = member(MAIN, "[\"editor\"]", "{}");
        UserId granted = member(MAIN, "[\"editor\"]", "{\"delete_users\":true}");
        assertThat(capabilities.isSuperAdmin(admin)).isTrue();
        assertThat(capabilities.isSuperAdmin(editor)).isFalse();
        assertThat(capabilities.isSuperAdmin(granted)).isTrue();
        assertThat(capabilities.userCan(admin, MAIN, "remove_user", Optional.of(new UserTarget(admin))))
                .isTrue();
        UserId remover = member(MAIN, "[\"editor\"]", "{\"remove_users\":true}");
        assertThat(capabilities.userCan(remover, MAIN, "remove_user", Optional.of(new UserTarget(remover))))
                .isFalse();
        assertThat(capabilities.userCan(admin, MAIN, "delete_site", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(admin, MAIN, "customize", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, MAIN, "view_site_health_checks", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(admin, MAIN, "create_sites", Optional.empty()))
                .isFalse();
    }

    @Test
    void aRemovedRoleIsAPlainCapability() {
        UserId user = member(MAIN, "[\"shop_manager\"]", "{}");
        assertThat(capabilities.userCan(user, MAIN, "shop_manager", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(user, MAIN, "read", Optional.empty())).isFalse();
    }

    @Test
    void explicitSiteIdsAreIgnoredOnASingleSite() {
        UserId editor = member(MAIN, "[\"editor\"]", "{}");
        SiteId elsewhere = SiteId.of(999_999);
        assertThat(capabilities.userCan(editor, elsewhere, "edit_others_posts", Optional.empty()))
                .isTrue();
        assertThat(capabilities.anonymousCan(elsewhere, "exist", Optional.empty()))
                .isTrue();
        assertThat(Identities.as(
                        identity(editor),
                        () -> capabilities.canForSite(identity(editor), elsewhere, "edit_posts", Optional.empty())))
                .isTrue();
    }

    @Test
    void postRulesUseTheFactsTheyAreGiven() {
        UserId contributor = member(MAIN, "[\"contributor\"]", "{}");
        PostFacts draft = PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "draft", Optional.of(contributor));
        PostFacts published =
                PostFacts.of(PostId.of(10), PostTypeCapabilities.POST, "publish", Optional.of(contributor));
        assertThat(capabilities.userCan(contributor, MAIN, "edit_post", Optional.of(draft)))
                .isTrue();
        assertThat(capabilities.userCan(contributor, MAIN, "edit_post", Optional.of(published)))
                .isFalse();
        TestData.siteOption(dataSource, MAIN, "page_on_front", "10");
        try {
            UserId admin = member(MAIN, "[\"administrator\"]", "{}");
            UserId editor = member(MAIN, "[\"editor\"]", "{}");
            PostFacts front = PostFacts.of(PostId.of(10), PostTypeCapabilities.PAGE, "publish", Optional.of(editor));
            assertThat(capabilities.userCan(editor, MAIN, "delete_post", Optional.of(front)))
                    .isFalse();
            assertThat(capabilities.userCan(admin, MAIN, "delete_post", Optional.of(front)))
                    .isTrue();
        } finally {
            TestData.siteOption(dataSource, MAIN, "page_on_front", "0");
        }
    }

    @Test
    void anIdWithoutProfileIsALoggedOutVisitor() {
        UserId ghost = UserId.of(Long.MAX_VALUE - 7);
        UserTarget self = new UserTarget(ghost);
        assertThat(capabilities.userCan(ghost, MAIN, "edit_user", Optional.of(self)))
                .isFalse();
        assertThat(capabilities.userCan(ghost, MAIN, "exist", Optional.empty())).isTrue();
        // an identity whose profile was deleted
        SecurityIdentity deleted = Identities.user(ghost, "deleted-subject");
        assertThat(Identities.as(deleted, () -> capabilities.can("edit_user", self)))
                .isFalse();
        assertThat(capabilities.canForSite(deleted, MAIN, "edit_user", Optional.of(self)))
                .isFalse();
        assertThat(capabilities.can(deleted, "exist", Optional.empty())).isTrue();
    }
}
