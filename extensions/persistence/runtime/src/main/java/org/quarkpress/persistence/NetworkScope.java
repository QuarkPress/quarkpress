package org.quarkpress.persistence;

import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.quarkpress.persistence.runtime.CallerCheck;
import org.quarkpress.persistence.runtime.Scopes;

/**
 * Runs work for the whole network: queries see the rows of every site, and inserts of site rows must name their site.
 * Only QuarkPress multisite, security and scheduling code may open it, plus callers that a {@link NetworkScopePolicy}
 * allows (the policy may be asked about a null caller when none can be found); anyone else gets a SecurityException.
 * This rule guards against mistakes; it is not a security boundary.
 *
 * <p>This is the only way into the network scope: {@code Scopes} opens it only with a {@link Key}, which only this
 * class can create. The work runs as described on {@link SiteScope}: on its own thread, request context and
 * transaction unless the caller is already in the network scope.
 */
@ApplicationScoped
public class NetworkScope {

    /** Proves to {@code Scopes} that the network scope is opened by this class. */
    public static final class Key {
        private Key() {}
    }

    private static final Key KEY = new Key();

    @Inject
    @All
    List<NetworkScopePolicy> policies;

    public void run(Runnable work) {
        Objects.requireNonNull(work, "work");
        checkCaller();
        try {
            Scopes.withinNetwork(KEY, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public <T> T call(Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        checkCaller();
        return Scopes.withinNetwork(KEY, work);
    }

    private void checkCaller() {
        Class<?> caller = CallerCheck.callerOf(NetworkScope.class);
        if (!CallerCheck.mayOpenNetwork(caller, policies)) {
            throw new SecurityException("the network scope is reserved for QuarkPress multisite, security and"
                    + " scheduling code; " + (caller == null ? "an unknown caller" : caller.getName())
                    + " may not open it");
        }
    }
}
