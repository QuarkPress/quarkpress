package org.quarkword.keycloak.provisioner.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

/** After a conflict, only a user of the requested username, ignoring case, counts as the existing user. */
class MatchingUserTest {

    private static UserRepresentation user(String id, String username) {
        UserRepresentation user = new UserRepresentation();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    void theUserOfTheUsernameIsFoundIgnoringCase() {
        assertThat(KeycloakUserProvisioner.matching(List.of(user("1", "jdoe")), "JDoe"))
                .contains("1");
        assertThat(KeycloakUserProvisioner.matching(List.of(user("1", "jdoe2"), user("2", "jdoe")), "jdoe"))
                .contains("2");
    }

    @Test
    void aSingleUserOfAnotherUsernameIsNotTaken() {
        assertThat(KeycloakUserProvisioner.matching(List.of(user("1", "someone")), "jdoe"))
                .isEmpty();
        assertThat(KeycloakUserProvisioner.matching(List.of(user("1", null)), "jdoe"))
                .isEmpty();
    }

    @Test
    void nobodyFoundIsEmpty() {
        assertThat(KeycloakUserProvisioner.matching(List.of(), "jdoe")).isEmpty();
    }
}
