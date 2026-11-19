package org.quarkpress.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.UserProvisioner;
import org.quarkpress.security.UserProvisioningException;
import org.quarkpress.security.UserProvisioningException.Reason;

/** A realm that does not exist in Keycloak is a configuration error, not a failure worth retrying. */
class KeycloakMissingRealmDbTest {

    private static final String MISSING = "no-such-realm-x7q";

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.app(KeycloakMissingRealmDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.keycloak-provisioner.realm", MISSING);

    @Inject
    UserProvisioner provisioner;

    @Test
    void aMissingRealmIsNotConfigured() {
        String name = ProvisionerApp.username();
        assertThatThrownBy(() -> provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS)))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.NOT_CONFIGURED);
                    assertThat(refused.getMessage())
                            .contains("quarkpress.keycloak-provisioner.realm")
                            .doesNotContain(MISSING)
                            .doesNotContain(name);
                    assertThat(refused.getCause()).isNull();
                });
    }
}
