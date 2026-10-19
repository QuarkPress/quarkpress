package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.DevMembershipSeeder;
import org.quarkpress.security.runtime.DevUsers;

class DevSeedMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(DevSeedMultisiteDbTest.class);

    @Inject
    DevMembershipSeeder seeder;

    @Inject
    UserProfiles profiles;

    @Inject
    Memberships memberships;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void inANetworkAliceIsANetworkAdministratorWithoutMembership() {
        for (DevUsers.DevUser user : DevUsers.ALL) {
            Jdbc.update(dataSource, "delete from qp_user_profile where subject = ?", user.id());
        }
        seeder.seed();
        UserProfile alice = profiles.findBySubject(DevUsers.ALL.get(0).id()).orElseThrow();
        assertThat(memberships.superAdmins()).contains(alice.id());
        assertThat(memberships.find(SiteId.of(1), alice.id())).isEmpty();
        UserProfile bob = profiles.findBySubject(DevUsers.ALL.get(1).id()).orElseThrow();
        assertThat(memberships.find(SiteId.of(1), bob.id()).orElseThrow().roles())
                .containsExactly("editor");
    }
}
