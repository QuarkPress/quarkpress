package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.LaunchMode;
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
import org.quarkpress.security.runtime.JpaMemberships;

class DevSeedDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(DevSeedDbTest.class);

    @Inject
    DevMembershipSeeder seeder;

    @Inject
    UserProfiles profiles;

    @Inject
    Memberships memberships;

    @Inject
    JpaMemberships roleSetter;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    /** Test applications of one JVM may share the database: start from no dev users. */
    static void forgetDevUsers(AgroalDataSource dataSource) {
        for (DevUsers.DevUser user : DevUsers.ALL) {
            Jdbc.update(dataSource, "delete from qp_user_profile where subject = ?", user.id());
        }
    }

    @Test
    void seedingGivesTheDevUsersTheirProfilesAndRolesIdempotently() {
        assertThat(LaunchMode.current()).isEqualTo(LaunchMode.TEST);
        forgetDevUsers(dataSource);
        seeder.seed();
        seeder.seed();
        SiteId main = SiteId.of(1);
        for (DevUsers.DevUser user : DevUsers.ALL) {
            UserProfile profile = profiles.findBySubject(user.id()).orElseThrow();
            assertThat(profile.nicename()).isEqualTo(user.username());
            assertThat(profile.displayName()).isEqualTo(user.givenName() + " " + user.familyName());
            assertThat(memberships.find(main, profile.id()).orElseThrow().roles())
                    .containsExactly(user.role());
        }
        assertThat(DevUsers.ALL)
                .extracting(DevUsers.DevUser::role)
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber");
    }

    @Test
    void aRoleChangedByHandIsSeededBackOnTheNextSeed() {
        forgetDevUsers(dataSource);
        seeder.seed();
        SiteId main = SiteId.of(1);
        UserProfile bob = profiles.findBySubject(DevUsers.ALL.get(1).id()).orElseThrow();
        roleSetter.setRoleWithoutCheck(main, bob.id(), "administrator");
        assertThat(memberships.find(main, bob.id()).orElseThrow().roles()).containsExactly("administrator");
        seeder.seed();
        assertThat(memberships.find(main, bob.id()).orElseThrow().roles()).containsExactly("editor");
    }
}
