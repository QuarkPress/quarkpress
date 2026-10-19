package org.quarkpress.security.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Objects;
import org.quarkpress.core.UserId;
import org.quarkpress.security.QuarkPressPrincipal;

/** Who a check is for: a logged-out visitor, a user, or the system. */
public sealed interface Actor {

    /** The identity attribute that marks the system identity. */
    String SYSTEM_ATTRIBUTE = "org.quarkpress.security.system";

    Actor ANONYMOUS = new Anonymous();

    Actor SYSTEM = new SystemActor();

    record Anonymous() implements Actor {}

    record User(UserId id) implements Actor {
        public User {
            Objects.requireNonNull(id, "id");
        }
    }

    record SystemActor() implements Actor {}

    /** The system for the system identity, the user of an identity with a QuarkPress principal, anonymous otherwise. */
    static Actor of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return ANONYMOUS;
        }
        if (Boolean.TRUE.equals(identity.getAttribute(SYSTEM_ATTRIBUTE))) {
            return SYSTEM;
        }
        return QuarkPressPrincipal.of(identity)
                .<Actor>map(principal -> new User(principal.user()))
                .orElse(ANONYMOUS);
    }
}
