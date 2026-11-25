package org.quarkword.front;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.UserId;

/**
 * Who looks at a front-end page: the logged-in user, if any, and the identity the page's queries and capability checks
 * run as. An anonymous viewer has no user and an anonymous identity.
 */
public record Viewer(Optional<UserId> user, SecurityIdentity identity) {

    private static final Viewer ANONYMOUS = new Viewer(
            Optional.empty(),
            QuarkusSecurityIdentity.builder()
                    .setAnonymous(true)
                    .setPrincipal(new QuarkusPrincipal(""))
                    .build());

    public Viewer {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(identity, "identity");
    }

    /** Nobody logged in. */
    public static Viewer anonymous() {
        return ANONYMOUS;
    }
}
