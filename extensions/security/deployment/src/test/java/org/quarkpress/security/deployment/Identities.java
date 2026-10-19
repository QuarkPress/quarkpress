package org.quarkpress.security.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.function.Supplier;
import org.quarkpress.core.UserId;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.runtime.Actor;

/** Identities for tests, and running code as one of them in a request context of its own. */
public final class Identities {

    private Identities() {}

    /** An identity of a profile, as the augmentor leaves it, without permission checker (enough for explicit checks). */
    public static SecurityIdentity user(UserId user, String subject) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, subject))
                .build();
    }

    /** The system identity as the evaluator recognises it (the attribute {@code SystemIdentity} will carry). */
    public static SecurityIdentity system() {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("system"))
                .addAttribute(Actor.SYSTEM_ATTRIBUTE, Boolean.TRUE)
                .build();
    }

    /** Runs {@code work} on the current thread in a fresh request context whose identity is {@code identity}. */
    public static <T> T as(SecurityIdentity identity, Supplier<T> work) {
        ManagedContext requests = Arc.container().requestContext();
        if (requests.isActive()) {
            throw new IllegalStateException("Identities.as needs a thread without a request context");
        }
        requests.activate();
        try {
            Arc.container().instance(CurrentIdentityAssociation.class).get().setIdentity(identity);
            return work.get();
        } finally {
            requests.terminate();
        }
    }
}
