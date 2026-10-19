package org.quarkpress.security;

/**
 * Finds or creates the profile of an authenticated subject. The default implementation names a new profile as
 * WordPress names a new user; replace it with an {@code @Alternative @Priority} bean to provision differently.
 */
public interface UserProfileProvisioner {

    /**
     * The subject's profile, created on its first call. Concurrent first calls for one subject create one profile;
     * concurrent new profiles whose nicenames collide get WordPress's numeric suffixes.
     */
    UserProfile provision(ProfileClaims claims);
}
