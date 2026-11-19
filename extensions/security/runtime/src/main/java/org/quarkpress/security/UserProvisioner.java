package org.quarkpress.security;

/**
 * Creates a user in the identity provider; QuarkPress's profile of that user is created by {@link UserImports}, not
 * here. Implement it with an {@code @ApplicationScoped} bean in an IdP module; QuarkPress calls it outside any
 * transaction and creates the profile itself. Not to be confused with {@link UserProfileProvisioner}, which shapes the
 * profile at login.
 *
 * <p>Without an IdP module the default bean refuses every call with
 * {@link UserProvisioningException.Reason#NOT_CONFIGURED}.
 */
public interface UserProvisioner {

    /**
     * Creates the user, or finds the identity-provider user of that username. Idempotent by username: an existing
     * user of that username is returned unchanged ({@link ProvisionedIdentity#created()} false), whatever the other
     * fields say, and its credentials are left as they are.
     *
     * @throws UserProvisioningException when the identity provider refuses the user or cannot be reached; its message
     *     is a fixed text that never carries the request or the password hash
     */
    ProvisionedIdentity provision(NewUser user);
}
