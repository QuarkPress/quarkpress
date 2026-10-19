package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.SuperAdminBootstrap;

class BootstrapSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(BootstrapSingleSiteDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.security.initial-super-admins", "boot-single");

    @Inject
    UserProfiles profiles;

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    SuperAdminBootstrap bootstrap;

    @Test
    void aListedSubjectAdministersTheMainSite() {
        UserProfile profile = profiles.findBySubject("boot-single").orElseThrow();
        SiteId main = SiteId.of(1);
        assertThat(memberships.find(main, profile.id()).orElseThrow().roles()).containsExactly("administrator");
        assertThat(capabilities.userCan(profile.id(), main, "manage_options", Optional.empty()))
                .isTrue();
        assertThat(memberships.superAdmins()).isEmpty();
        assertThat(profile.lastLoginAt()).isEmpty();
        bootstrap.apply();
        assertThat(memberships.find(main, profile.id()).orElseThrow().roles()).containsExactly("administrator");
        assertThat(capabilities.isSuperAdmin(profile.id())).isTrue();
    }
}
