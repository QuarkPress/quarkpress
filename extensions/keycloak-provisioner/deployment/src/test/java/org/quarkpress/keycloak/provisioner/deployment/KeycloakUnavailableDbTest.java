package org.quarkpress.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.UserProvisioner;
import org.quarkpress.security.UserProvisioningException;
import org.quarkpress.security.UserProvisioningException.Reason;

/**
 * Keycloak not answering (here, nothing listening on the admin client's port) is reported as unavailable, with a fixed
 * text. The admin client has no timeout setting; a refused connection fails at once.
 */
class KeycloakUnavailableDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.withoutKeycloak(KeycloakUnavailableDbTest.class);

    @Inject
    UserProvisioner provisioner;

    @Test
    void aKeycloakThatCannotBeReachedIsUnavailable() {
        String name = ProvisionerApp.username();
        long start = System.nanoTime();
        assertThatThrownBy(() -> provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS)))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.UNAVAILABLE);
                    // the network error is kept as the cause: it names the address, never the request
                    assertThat(refused.getCause()).isInstanceOf(java.net.ConnectException.class);
                    assertThat(Failures.messages(refused)).doesNotContain(name).doesNotContain(ProvisionerApp.PHPASS);
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }
}
