package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SystemIdentity;

class SuperAdminsSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(SuperAdminsSingleSiteDbTest.class, TestData.class);

    @Inject
    Memberships memberships;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void aSingleSiteHasNoNetworkAdministrators() throws Exception {
        UserId user = TestData.profile(dataSource, "single");
        assertThat(system.call(() -> memberships.grantSuperAdmin(user))).isFalse();
        assertThat(system.call(() -> memberships.revokeSuperAdmin(user))).isFalse();
        assertThat(memberships.superAdmins()).isEmpty();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", user.value()))
                .isZero();
    }
}
