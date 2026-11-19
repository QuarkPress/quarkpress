package org.quarkpress.security;

import java.util.Objects;

/**
 * The outcome of {@link UserImports#importUser}: the user's profile, whether the identity-provider user was created by
 * this import, and whether the profile was.
 */
public record ImportedUser(UserProfile profile, boolean createdInIdentityProvider, boolean profileCreated) {

    public ImportedUser {
        Objects.requireNonNull(profile, "profile");
    }
}
