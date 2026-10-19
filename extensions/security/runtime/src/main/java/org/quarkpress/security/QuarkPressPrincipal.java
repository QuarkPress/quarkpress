package org.quarkpress.security;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.UserId;

/** The QuarkPress user behind an authenticated identity: its profile id and OIDC subject. */
public record QuarkPressPrincipal(UserId user, String subject) {

    /** The identity attribute that holds it. */
    public static final String ATTRIBUTE = "org.quarkpress.security.principal";

    public QuarkPressPrincipal {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(subject, "subject");
    }

    public static Optional<QuarkPressPrincipal> of(SecurityIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return identity.getAttribute(ATTRIBUTE) instanceof QuarkPressPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }
}
