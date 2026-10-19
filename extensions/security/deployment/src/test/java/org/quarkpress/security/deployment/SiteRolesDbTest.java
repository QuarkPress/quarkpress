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
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Roles;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.SystemIdentity;

class SiteRolesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(SiteRolesDbTest.class, TestData.class, Identities.class);

    @Inject
    SiteRoles siteRoles;

    @Inject
    Roles roles;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity administratorOf(SiteId site) {
        UserId admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, site, admin, "[\"administrator\"]", "{}");
        return Identities.user(admin, TestData.subject(dataSource, admin));
    }

    private long storedTables(SiteId site) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_option where site_id = ? and name = 'qp_user_roles'",
                site.value());
    }

    @Test
    void anUntouchedSiteHasTheDefaultTable() {
        SiteId site = TestData.site(dataSource);
        assertThat(siteRoles.roles(site)).isEqualTo(roles.defaults());
        assertThat(siteRoles.role(site, "editor")).isEqualTo(roles.defaultRole("editor"));
        assertThat(siteRoles.role(site, "Editor")).isEmpty();
        assertThat(storedTables(site)).isZero();
    }

    @Test
    void addingARoleStoresTheWholeTable() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        Optional<RoleDefinition> added =
                Identities.as(admin, () -> siteRoles.add(site, "shop_manager", "Shop manager", Map.of("read", true)));
        assertThat(added).contains(new RoleDefinition("shop_manager", "Shop manager", Map.of("read", true)));
        assertThat(siteRoles.roles(site))
                .extracting(RoleDefinition::key)
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber", "shop_manager");
        assertThat(storedTables(site)).isEqualTo(1);
        assertThat(Identities.as(admin, () -> siteRoles.add(site, "shop_manager", "Other", Map.of())))
                .isEmpty();
        assertThat(Identities.as(admin, () -> siteRoles.add(site, "editor", "Chief", Map.of("read", true))))
                .isEmpty();
        assertThat(siteRoles.role(site, "editor").orElseThrow().displayName()).isEqualTo("Editor");
        assertThat(Identities.as(admin, () -> siteRoles.add(site, "", "Nothing", Map.of())))
                .isEmpty();
    }

    @Test
    void removingARoleResetsTheDefaultRoleThatNamedIt() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        TestData.siteOption(dataSource, site, "default_role", "\"author\"");
        assertThat(Identities.as(admin, () -> siteRoles.remove(site, "author"))).isTrue();
        assertThat(siteRoles.role(site, "author")).isEmpty();
        assertThat(Jdbc.strings(
                        dataSource,
                        "select value from qp_option where site_id = ? and name = 'default_role'",
                        site.value()))
                .containsExactly("\"subscriber\"");
        assertThat(Identities.as(admin, () -> siteRoles.remove(site, "author"))).isFalse();
        UserId formerAuthor = TestData.profile(dataSource, "former");
        TestData.member(dataSource, site, formerAuthor, "[\"author\"]", "{}");
        assertThat(capabilities.userCan(formerAuthor, site, "edit_posts", Optional.empty()))
                .isFalse();
        assertThat(capabilities.userCan(formerAuthor, site, "author", Optional.empty()))
                .isTrue();
    }

    @Test
    void aRoleCapabilityChangeIsVisibleToTheNextCheckOnThatSiteOnly() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]", "{}");
        TestData.member(dataSource, other, editor, "[\"editor\"]", "{}");
        assertThat(Identities.as(admin, () -> siteRoles.setCapability(site, "editor", "edit_theme_options", true)))
                .isTrue();
        assertThat(capabilities.userCan(editor, site, "edit_theme_options", Optional.empty()))
                .isTrue();
        assertThat(capabilities.userCan(editor, other, "edit_theme_options", Optional.empty()))
                .isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.setCapability(site, "editor", "publish_posts", false)))
                .isTrue();
        assertThat(capabilities.userCan(editor, site, "publish_posts", Optional.empty()))
                .isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.setCapability(site, "nobody", "read", true)))
                .isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.removeCapability(site, "editor", "edit_theme_options")))
                .isTrue();
        assertThat(Identities.as(admin, () -> siteRoles.removeCapability(site, "editor", "edit_theme_options")))
                .isFalse();
        assertThat(capabilities.userCan(editor, site, "edit_theme_options", Optional.empty()))
                .isFalse();
    }

    @Test
    void changesNeedManageOptionsOnTheSite() {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]", "{}");
        assertThatThrownBy(() -> Identities.as(
                        Identities.user(editor, TestData.subject(dataSource, editor)),
                        () -> siteRoles.remove(site, "author")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_options on site " + site.value());
        assertThatThrownBy(() -> Identities.as(admin, () -> siteRoles.remove(other, "author")))
                .isInstanceOf(ForbiddenException.class);
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThatThrownBy(() -> Identities.as(anonymous, () -> siteRoles.remove(site, "author")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(storedTables(other)).isZero();
    }

    @Test
    void concurrentChangesOfOneTableAreAllKept() throws Exception {
        SiteId site = TestData.site(dataSource);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String capability = "custom_" + i;
            tasks.add(() -> system.call(() -> siteRoles.setCapability(site, "subscriber", capability, true)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).containsOnly(Boolean.TRUE);
        assertThat(siteRoles.role(site, "subscriber").orElseThrow().capabilities())
                .containsKeys("custom_0", "custom_1", "custom_2", "custom_3", "custom_4", "custom_5");
    }

    @Test
    void aChangeThatChangesNothingStoresNothing() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        assertThat(Identities.as(admin, () -> siteRoles.remove(site, "nope"))).isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.add(site, "editor", "Chief", Map.of())))
                .isEmpty();
        assertThat(Identities.as(admin, () -> siteRoles.setCapability(site, "nobody", "read", true)))
                .isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.removeCapability(site, "editor", "no_such_cap")))
                .isFalse();
        assertThat(Identities.as(admin, () -> siteRoles.removeCapability(site, "nobody", "read")))
                .isFalse();
        assertThat(storedTables(site)).isZero();
    }

    @Test
    void theRoleKeyZeroIsRefusedLikeTheEmptyKey() {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity admin = administratorOf(site);
        assertThat(Identities.as(admin, () -> siteRoles.add(site, "0", "Zero", Map.of())))
                .isEmpty();
        assertThat(siteRoles.role(site, "0")).isEmpty();
        assertThat(storedTables(site)).isZero();
    }
}
