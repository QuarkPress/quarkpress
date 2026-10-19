package org.quarkpress.security;

import java.time.Instant;
import java.util.Objects;

/**
 * A profile was created (WordPress's {@code user_register}). Profiles belong to the network, so the event carries no
 * site.
 */
public record UserProfileCreated(Instant at, UserProfile profile) {

    public UserProfileCreated {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(profile, "profile");
    }
}
