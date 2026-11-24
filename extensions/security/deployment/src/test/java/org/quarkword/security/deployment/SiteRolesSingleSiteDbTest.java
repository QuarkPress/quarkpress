package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.security.SiteRoles;

/** On a single site WordPress has no site dimension: whatever id is given, the main site's roles are meant. */
class SiteRolesSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.with(SiteRolesSingleSiteDbTest.class, TestData.class, Identities.class);

    @Inject
    SiteRoles siteRoles;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void aChangeThroughAnotherIdReachesTheMainSite() {
        SiteId main = SiteId.of(1);
        SiteId other = SiteId.of(7);
        UserId admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, main, admin, "[\"administrator\"]", "{}");
        SecurityIdentity identity = Identities.user(admin, TestData.subject(dataSource, admin));
        assertThat(Identities.as(identity, () -> siteRoles.setCapability(other, "editor", "custom_cap", true)))
                .isTrue();
        assertThat(siteRoles.role(main, "editor").orElseThrow().capabilities()).containsKey("custom_cap");
        assertThat(siteRoles.role(other, "editor").orElseThrow().capabilities()).containsKey("custom_cap");
        assertThat(Jdbc.count(dataSource, "select count(*) from qw_option where name = 'qw_user_roles'"))
                .isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qw_option where site_id = ? and name = 'qw_user_roles'",
                        main.value()))
                .isEqualTo(1);
    }
}
