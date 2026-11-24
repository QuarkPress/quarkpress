package org.quarkword.keycloak.provisioner.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.quarkword.security.PasswordHash;

/** The Keycloak credentials QuarkWord imports with a user. */
public final class CredentialRepresentations {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CredentialRepresentations() {}

    /**
     * A password credential holding a WordPress hash for Keycloak's {@code wordpress} hash provider (the
     * quarkword-keycloak provider), which verifies it on sign-in.
     *
     * <p>The stored hash iterations are 0 and the algorithm data empty, never copied from anywhere: Keycloak's
     * default hash provider then finds the credential below its policy after the first successful sign-in and
     * re-hashes the password with the realm's algorithm. No salt is sent; Keycloak stores none for such a credential.
     *
     * @throws IllegalArgumentException when {@code hash} is not a WordPress hash
     */
    public static CredentialRepresentation wordpress(PasswordHash hash) {
        Objects.requireNonNull(hash, "hash");
        if (!PasswordHash.WORDPRESS.equals(hash.algorithm())) {
            throw new IllegalArgumentException("not a WordPress password hash");
        }
        ObjectNode credentialData = JSON.createObjectNode();
        credentialData.put("hashIterations", 0);
        credentialData.put("algorithm", PasswordHash.WORDPRESS);
        credentialData.putObject("algorithmData");
        ObjectNode secretData = JSON.createObjectNode();
        secretData.put("value", hash.value());
        secretData.putObject("algorithmData");

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        // permanent: Keycloak asks for no password update at the first sign-in
        credential.setTemporary(false);
        credential.setCredentialData(write(credentialData));
        credential.setSecretData(write(secretData));
        return credential;
    }

    private static String write(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // the cause's message could quote the value
            throw new IllegalStateException("the credential could not be written as JSON");
        }
    }
}
