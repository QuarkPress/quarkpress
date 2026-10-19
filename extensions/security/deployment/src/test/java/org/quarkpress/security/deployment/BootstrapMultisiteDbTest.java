package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.NetworkAdmins;
import org.quarkpress.security.runtime.SuperAdminBootstrap;

class BootstrapMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(BootstrapMultisiteDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.security.initial-super-admins", "boot-net-a,boot-net-b,boot-net-a");

    @Inject
    UserProfiles profiles;

    @Inject
    SuperAdminBootstrap bootstrap;

    @Inject
    NetworkAdmins admins;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private long adminRows(String subject) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_network_admin a join qp_user_profile p on p.id = a.user_id"
                        + " where p.subject = ?",
                subject);
    }

    private List<String> lastLogin(String subject) {
        return Jdbc.strings(dataSource, "select last_login_at from qp_user_profile where subject = ?", subject);
    }

    @Test
    void theBootstrapRecordsNoLogin() {
        assertThat(profiles.findBySubject("boot-net-b").orElseThrow().lastLoginAt())
                .isEmpty();
        Jdbc.update(
                dataSource,
                "update qp_user_profile set last_login_at = '2020-01-02 03:04:05' where subject = ?",
                "boot-net-b");
        List<String> before = lastLogin("boot-net-b");
        bootstrap.apply();
        assertThat(lastLogin("boot-net-b")).isEqualTo(before).doesNotContainNull();
    }

    @Test
    void listedSubjectsBecomeNetworkAdministratorsAtStartAndAgainAfterARevoke() {
        UserProfile a = profiles.findBySubject("boot-net-a").orElseThrow();
        assertThat(a.nicename()).isEqualTo("user-" + a.id().value());
        assertThat(a.displayName()).isEqualTo("boot-net-a");
        assertThat(adminRows("boot-net-a")).isEqualTo(1);
        assertThat(adminRows("boot-net-b")).isEqualTo(1);
        bootstrap.apply();
        assertThat(adminRows("boot-net-a")).isEqualTo(1);
        assertThat(admins.revoke(a.id())).isTrue();
        assertThat(adminRows("boot-net-a")).isZero();
        bootstrap.apply();
        assertThat(adminRows("boot-net-a")).isEqualTo(1);
    }
}
