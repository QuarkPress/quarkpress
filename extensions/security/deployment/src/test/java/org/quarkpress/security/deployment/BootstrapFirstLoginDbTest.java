package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.ProfileProvisioning;

/**
 * A profile the super-admin bootstrap made from a bare subject takes its names from the token at its first login, as
 * a new profile does; later logins leave the names alone.
 */
class BootstrapFirstLoginDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(BootstrapFirstLoginDbTest.class)
            .overrideRuntimeConfigKey(
                    "quarkpress.security.initial-super-admins", "boot-login-a,boot-login-b,boot-login-c,boot-login-d");

    @Inject
    UserProfiles profiles;

    @Inject
    ProfileProvisioning provisioning;

    private static ProfileClaims claims(String subject, String username, String given, String family) {
        return new ProfileClaims(
                subject, Optional.ofNullable(username), Optional.ofNullable(given), Optional.ofNullable(family));
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    void theFirstLoginNamesABootstrapProfileAndLaterLoginsDoNot() {
        UserProfile before = profiles.findBySubject("boot-login-a").orElseThrow();
        assertThat(before.displayName()).isEqualTo("boot-login-a");
        assertThat(before.nicename()).isEqualTo("user-" + before.id().value());
        String username = "Ann.Boot" + token();

        UserProfile first = provisioning.provision(claims("boot-login-a", username, "Ann", "Boot"), true);
        assertThat(first.id()).isEqualTo(before.id());
        assertThat(first.displayName()).isEqualTo("Ann Boot");
        assertThat(first.nicename())
                .isEqualTo(username.toLowerCase(java.util.Locale.ROOT).replace('.', '-'));
        assertThat(first.lastLoginAt()).isPresent();

        UserProfile again = provisioning.provision(claims("boot-login-a", "other" + token(), "Changed", null), true);
        assertThat(again.displayName()).isEqualTo("Ann Boot");
        assertThat(again.nicename()).isEqualTo(first.nicename());
    }

    @Test
    void aTakenNicenameGetsASuffixAndAUsernameWithoutOneKeepsTheFallback() {
        String username = "bootbob" + token();
        UserProfile other = provisioning.provision(claims("sub-" + token(), username, "Bob", null), true);
        assertThat(other.nicename()).isEqualTo(username);

        UserProfile b = provisioning.provision(claims("boot-login-b", username, "Bea", null), true);
        assertThat(b.displayName()).isEqualTo("Bea");
        assertThat(b.nicename()).isEqualTo(username + "-2");

        UserProfile c = profiles.findBySubject("boot-login-c").orElseThrow();
        UserProfile named = provisioning.provision(claims("boot-login-c", null, null, null), true);
        assertThat(named.displayName()).isEqualTo("boot-login-c");
        assertThat(named.nicename()).isEqualTo(c.nicename()).isEqualTo("user-" + c.id().value());
    }

    @Test
    void aProfileNotMadeByTheBootstrapKeepsItsNamesAtLogin() {
        String subject = "sub-" + token();
        UserProfile created = provisioning.provision(claims(subject, "carla" + token(), "Carla", null), false);
        UserProfile login = provisioning.provision(claims(subject, "renamed" + token(), "Renamed", null), true);
        assertThat(login.displayName()).isEqualTo("Carla");
        assertThat(login.nicename()).isEqualTo(created.nicename());
    }

    @Test
    void aNeverLoggedInProfileThatOnlyLooksLikeABootstrapOneIsNotRenamed() {
        // made without a login from a bare subject, as the bootstrap makes its own, but not by the bootstrap
        String subject = "sub-" + token();
        UserProfile bare = provisioning.provision(ProfileClaims.subjectOnly(subject), false);
        assertThat(bare.displayName()).isEqualTo(subject);
        assertThat(bare.nicename()).isEqualTo("user-" + bare.id().value());
        assertThat(bare.lastLoginAt()).isEmpty();
        UserProfile login = provisioning.provision(claims(subject, "named" + token(), "Named", "User"), true);
        assertThat(login.displayName()).isEqualTo(subject);
        assertThat(login.nicename()).isEqualTo(bare.nicename());
    }

    @Test
    void aProfileFirstLoggedInWithABareTokenIsNeverRenamedLater() {
        String subject = "sub-" + token();
        UserProfile first = provisioning.provision(ProfileClaims.subjectOnly(subject), true);
        // a later login with names and a new e-mail writes the row, but keeps the names
        UserProfile later = provisioning.provision(
                new ProfileClaims(
                        subject,
                        Optional.of("later" + token()),
                        Optional.of("Later"),
                        Optional.empty(),
                        Optional.of("later@quarkpress.test")),
                true);
        assertThat(later.email()).contains("later@quarkpress.test");
        assertThat(later.displayName()).isEqualTo(first.displayName()).isEqualTo(subject);
        assertThat(later.nicename()).isEqualTo(first.nicename());
    }

    @Test
    void aLoginOfOneSubjectNeverNamesAnotherBootstrapProfile() {
        UserProfile d = profiles.findBySubject("boot-login-d").orElseThrow();
        String username = "dora" + token();
        // another subject logs in with the username d would get
        UserProfile other = provisioning.provision(claims("sub-" + token(), username, "Other", null), true);
        assertThat(other.id()).isNotEqualTo(d.id());
        UserProfile still = profiles.findBySubject("boot-login-d").orElseThrow();
        assertThat(still.displayName()).isEqualTo("boot-login-d");
        assertThat(still.nicename()).isEqualTo(d.nicename());
        // d's own first login still names it, past the taken nicename
        UserProfile named = provisioning.provision(claims("boot-login-d", username, "Dora", null), true);
        assertThat(named.displayName()).isEqualTo("Dora");
        assertThat(named.nicename()).isEqualTo(username + "-2");
    }
}
