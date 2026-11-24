package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.security.Memberships;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;
import org.quarkword.security.runtime.DevMembershipSeeder;
import org.quarkword.security.runtime.DevUsers;

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
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void inANetworkAliceIsANetworkAdministratorWithoutMembership() {
        for (DevUsers.DevUser user : DevUsers.ALL) {
            Jdbc.update(dataSource, "delete from qw_user_profile where subject = ?", user.id());
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
