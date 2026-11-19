package org.quarkpress.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The provisioner declares its keys to core: an unknown key under {@code quarkpress.keycloak-provisioner} fails the
 * start, naming it, while the two keys it reads (set here) are accepted.
 */
class ProvisionerConfigKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.withoutKeycloak(ProvisionerConfigKeysTest.class)
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false")
            .overrideRuntimeConfigKey("quarkpress.keycloak-provisioner.realm", "r")
            .overrideRuntimeConfigKey("quarkpress.keycloak-provisioner.email-verified", "false")
            .overrideRuntimeConfigKey("quarkpress.keycloak-provisioner.nope", "x")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    // the only unknown one: the two keys the provisioner reads are declared
                    .contains("Unknown QuarkPress setting quarkpress.keycloak-provisioner.nope: "));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
