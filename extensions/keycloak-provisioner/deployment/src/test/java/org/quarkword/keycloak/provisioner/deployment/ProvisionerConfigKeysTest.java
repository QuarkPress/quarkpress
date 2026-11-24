package org.quarkword.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The provisioner declares its keys to core: an unknown key under {@code quarkword.keycloak-provisioner} fails the
 * start, naming it, while the two keys it reads (set here) are accepted.
 */
class ProvisionerConfigKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.withoutKeycloak(ProvisionerConfigKeysTest.class)
            .overrideConfigKey("quarkus.datasource.\"quarkword\".devservices.enabled", "false")
            .overrideRuntimeConfigKey("quarkword.keycloak-provisioner.realm", "r")
            .overrideRuntimeConfigKey("quarkword.keycloak-provisioner.email-verified", "false")
            .overrideRuntimeConfigKey("quarkword.keycloak-provisioner.nope", "x")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    // the only unknown one: the two keys the provisioner reads are declared
                    .contains("Unknown QuarkWord setting quarkword.keycloak-provisioner.nope: "));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
