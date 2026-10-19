package org.quarkpress.security;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.UserId;

/**
 * A user became a network administrator ({@code granted}) or stopped being one (WordPress's
 * {@code granted_super_admin} and {@code revoked_super_admin}). Network data: no site.
 */
public record SuperAdminChanged(Instant at, UserId user, boolean granted) {

    public SuperAdminChanged {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(user, "user");
    }
}
