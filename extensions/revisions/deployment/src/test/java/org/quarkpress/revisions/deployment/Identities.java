package org.quarkpress.revisions.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.concurrent.Callable;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;

/** Identities for tests, and running code bound to a site as one of them. */
public final class Identities {

    private Identities() {}

    /** A profile's identity as the augmentor leaves it, without permission checker (enough for explicit checks). */
    public static SecurityIdentity user(UserId user) {
        String subject = "user-" + user.value();
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, subject))
                .build();
    }

    public static SecurityIdentity system() {
        return Arc.container().instance(SystemIdentity.class).get().identity();
    }

    public static SecurityIdentity anonymous() {
        return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    }

    /**
     * Runs {@code work} bound to {@code site} as {@code identity}: through {@code SiteScope}, so on a thread with a
     * request context and a transaction of its own (call it from a test thread without a request context).
     */
    public static <T> T onSite(SiteId site, SecurityIdentity identity, Callable<T> work) throws Exception {
        SiteScope scope = Arc.container().instance(SiteScope.class).get();
        return scope.call(site, () -> {
            Arc.container().instance(CurrentIdentityAssociation.class).get().setIdentity(identity);
            return work.call();
        });
    }
}
