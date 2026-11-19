package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserImports;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.UserProvisioningException;

/**
 * Without an identity-provider module the default provisioner refuses, and the import creates no profile. The imports
 * service is looked up programmatically, as a host application or import job may look it up.
 */
class UserImportsNotConfiguredDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(UserImportsNotConfiguredDbTest.class, ImportUsers.class);

    @Inject
    SystemIdentity system;

    @Inject
    UserProfiles profiles;

    @Test
    void theDefaultProvisionerRefusesAsNotConfigured() {
        String name = ImportUsers.word();
        // looked up, not injected: nothing in this application injects UserImports, so it must be unremovable
        UserImports imports = Arc.container().instance(UserImports.class).get();
        assertThat(imports).isNotNull();
        assertThatThrownBy(() -> system.call(() -> imports.importUser(ImportUsers.john(name))))
                .isInstanceOfSatisfying(UserProvisioningException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(UserProvisioningException.Reason.NOT_CONFIGURED);
                    assertThat(failure.getMessage())
                            .isEqualTo("No identity provider module provisions users;"
                                    + " install quarkpress-keycloak-provisioner.");
                });
        assertThat(profiles.findByNicename(name)).isEmpty();
    }
}
