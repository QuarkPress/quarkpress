package org.quarkpress.security.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfileProvisioner;

/**
 * Finds or creates a profile at login, in a transaction of its own (committed whatever the caller's transaction does),
 * as {@link ProfileProvisioning} does: a new profile is named as WordPress names a new user, and the login time is
 * written on creation and refreshed when it is older than {@link #LOGIN_REFRESH}. Concurrent first logins of one
 * subject and colliding nicenames never fail.
 */
@DefaultBean
@ApplicationScoped
public class DefaultUserProfileProvisioner implements UserProfileProvisioner {

    /** How old the stored login time must be before a login writes it again. */
    public static final Duration LOGIN_REFRESH = ProfileProvisioning.LOGIN_REFRESH;

    @Inject
    ProfileProvisioning provisioning;

    /**
     * Finds or creates the profile. Do not call it inside a transaction that already holds the subject's or
     * nicename's profile row (inserted or locked): the transaction of its own waits for that row and can deadlock.
     */
    @Override
    public UserProfile provision(ProfileClaims claims) {
        return provisioning.provision(claims, true);
    }
}
