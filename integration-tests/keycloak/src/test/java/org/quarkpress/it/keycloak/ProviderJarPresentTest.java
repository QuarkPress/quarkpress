package org.quarkpress.it.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Fails when the end-to-end tests would be skipped although the provider is required ({@code
 * -DrequireKeycloakProvider=true}, as CI sets it), so that a missing jar never passes silently there.
 */
class ProviderJarPresentTest {

    @Test
    void theProviderJarAndFixturesArePresentWhereRequired() {
        if (Boolean.getBoolean(ProviderJarCondition.REQUIRE)) {
            assertThat(ProviderJarCondition.problems())
                    .as(
                            "the end-to-end tests need the quarkpress-keycloak checkout (-D%s=true)",
                            ProviderJarCondition.REQUIRE)
                    .isEmpty();
        }
    }
}
