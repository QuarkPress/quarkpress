package org.quarkpress.keycloak.provisioner.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.quarkpress.security.PasswordHash;

/** The imported credential: a non-temporary password credential for the wordpress algorithm. */
class CredentialRepresentationsTest {

    private static final String PHPASS = "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1";

    @Test
    void theCredentialIsAPermanentWordPressPassword() {
        CredentialRepresentation credential = CredentialRepresentations.wordpress(PasswordHash.wordpress(PHPASS));

        assertThat(credential.getType()).isEqualTo("password");
        assertThat(credential.isTemporary()).isFalse();
        assertThat(credential.getCredentialData())
                .isEqualTo("{\"hashIterations\":0,\"algorithm\":\"wordpress\",\"algorithmData\":{}}");
        assertThat(credential.getSecretData()).isEqualTo("{\"value\":\"" + PHPASS + "\",\"algorithmData\":{}}");
        assertThat(credential.getValue()).isNull();
    }
}
