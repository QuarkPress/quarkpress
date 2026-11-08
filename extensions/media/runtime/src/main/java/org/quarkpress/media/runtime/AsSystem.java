package org.quarkpress.media.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.quarkpress.security.SystemIdentity;

/**
 * Runs a read as the system, which reads every status, on the calling thread: the system identity is put on the
 * current request context for the read and the caller's identity is put back after it, also when the read throws.
 * Without an active request context, one is activated for the read and ended after it. The caller's capability is not
 * asked.
 */
@ApplicationScoped
public class AsSystem {

    @Inject
    SystemIdentity system;

    @Inject
    CurrentIdentityAssociation identities;

    /** {@code read}'s answer, read as the system. */
    public <T> T call(Supplier<T> read) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (!requestContext.isActive()) {
            requestContext.activate();
            try {
                return inContext(read);
            } finally {
                requestContext.terminate();
            }
        }
        return inContext(read);
    }

    private <T> T inContext(Supplier<T> read) {
        SecurityIdentity caller = identities.getIdentity();
        identities.setIdentity(system.identity());
        try {
            return read.get();
        } finally {
            identities.setIdentity(caller);
        }
    }
}
