package org.quarkword.security;

/**
 * Imports a user, as WordPress's importers create one with {@code wp_insert_user}: the caller must hold
 * {@code create_users} (in a network, by default only network administrators and the system identity do), then the
 * {@link UserProvisioner} creates or finds the identity-provider user, then QuarkWord creates its profile.
 */
public interface UserImports {

    /**
     * Imports {@code user}. Call it outside any transaction: the identity provider is called with any transaction of
     * the caller suspended, and the profile is written in a transaction of its own. Importing a username again
     * returns the identity-provider user and profile that exist, unchanged, so a failed import is retried by calling
     * again.
     *
     * @throws org.quarkword.core.CapabilityDeniedException when the caller lacks {@code create_users}; the identity
     *     provider is not called
     * @throws UserProvisioningException when the identity provider refuses the user or cannot be reached; no profile
     *     is created
     */
    ImportedUser importUser(NewUser user);
}
