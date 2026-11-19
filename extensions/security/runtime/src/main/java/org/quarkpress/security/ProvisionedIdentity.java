package org.quarkpress.security;

import java.util.Objects;

/**
 * The identity-provider user a {@link UserProvisioner} made or found: its OIDC subject ({@code sub}) and whether this
 * call created it.
 */
public record ProvisionedIdentity(String subject, boolean created) {

    public ProvisionedIdentity {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("an OIDC subject must not be blank");
        }
    }
}
