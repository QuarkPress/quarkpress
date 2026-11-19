package org.quarkpress.keycloak.provisioner.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Keycloak's refusal of a user's fields becomes a fixed text: only known field names and message keys pass. */
class InvalidUserMessageTest {

    private static final String GENERIC = "Keycloak refused the user.";

    @Test
    void aKnownFieldAndKeyAreNamed() {
        assertThat(KeycloakUserProvisioner.invalidUserMessage(
                        "{\"field\":\"username\",\"errorMessage\":\"error-username-invalid-character\","
                                + "\"params\":[\"username\"]}"))
                .isEqualTo("Keycloak refused the user's username: error-username-invalid-character.");
        assertThat(KeycloakUserProvisioner.invalidUserMessage(
                        "{\"field\":\"username\",\"errorMessage\":\"error-invalid-length\"}"))
                .isEqualTo("Keycloak refused the user's username: error-invalid-length.");
    }

    @Test
    void anUnknownKeyAndFieldAreLeftOut() {
        String hostile = "{\"field\":\"<b>$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1</b>\","
                + "\"errorMessage\":\"$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1\"}";
        assertThat(KeycloakUserProvisioner.invalidUserMessage(hostile)).isEqualTo(GENERIC);
        assertThat(KeycloakUserProvisioner.invalidUserMessage(
                        "{\"field\":\"username\",\"errorMessage\":\"User exists with same username jdoe\"}"))
                .isEqualTo("Keycloak refused the user's username.");
        assertThat(KeycloakUserProvisioner.invalidUserMessage(
                        "{\"field\":\"password\",\"errorMessage\":\"error-invalid-length\"}"))
                .isEqualTo("Keycloak refused the user: error-invalid-length.");
    }

    @Test
    void anUnreadableBodyGivesTheGenericText() {
        assertThat(KeycloakUserProvisioner.invalidUserMessage("<html>error-invalid-length</html>"))
                .isEqualTo(GENERIC);
        assertThat(KeycloakUserProvisioner.invalidUserMessage("")).isEqualTo(GENERIC);
        assertThat(KeycloakUserProvisioner.invalidUserMessage(null)).isEqualTo(GENERIC);
        assertThat(KeycloakUserProvisioner.invalidUserMessage("[\"error-invalid-length\"]"))
                .isEqualTo(GENERIC);
        assertThat(KeycloakUserProvisioner.invalidUserMessage("{\"errorMessage\":{\"x\":1}}"))
                .isEqualTo(GENERIC);
    }
}
