package org.quarkword.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.security.UserProvisioner;
import org.quarkword.security.UserProvisioningException;
import org.quarkword.security.UserProvisioningException.Reason;

/** Keycloak refusing the admin client's credentials is reported as such, with a fixed text. */
class KeycloakUnauthorizedDbTest {

    private static final String WRONG = "not-the-admin-password";

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.app(KeycloakUnauthorizedDbTest.class)
            .overrideRuntimeConfigKey("quarkus.keycloak.admin-client.password", WRONG);

    @Inject
    UserProvisioner provisioner;

    @Test
    void aWrongAdminPasswordIsUnauthorized() {
        String name = ProvisionerApp.username();
        assertThatThrownBy(() -> provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS)))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.UNAUTHORIZED);
                    assertThat(Failures.messages(refused))
                            .doesNotContain(name)
                            .doesNotContain(WRONG)
                            .doesNotContain(ProvisionerApp.PHPASS);
                });
    }
}
