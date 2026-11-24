package org.quarkword.security;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.UserId;

/** The QuarkWord user behind an authenticated identity: its profile id and OIDC subject. */
public record QuarkWordPrincipal(UserId user, String subject) {

    /** The identity attribute that holds it. */
    public static final String ATTRIBUTE = "org.quarkword.security.principal";

    public QuarkWordPrincipal {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(subject, "subject");
    }

    public static Optional<QuarkWordPrincipal> of(SecurityIdentity identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return identity.getAttribute(ATTRIBUTE) instanceof QuarkWordPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }
}
