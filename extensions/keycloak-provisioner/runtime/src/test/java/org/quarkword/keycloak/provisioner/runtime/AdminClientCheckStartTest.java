package org.quarkword.keycloak.provisioner.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

/**
 * The start-up observer itself: outside Quarkus the launch mode is production and the configuration holds only the
 * admin client's defaults, so the start is refused with both problems.
 */
class AdminClientCheckStartTest {

    @Test
    void theDefaultsFailTheStartInProduction() {
        assertThat(LaunchMode.current()).isEqualTo(LaunchMode.NORMAL);

        assertThatThrownBy(() -> new AdminClientCheck().onStart(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default Keycloak admin credentials")
                .hasMessageContaining("quarkword.keycloak-provisioner.realm is required");
    }
}
