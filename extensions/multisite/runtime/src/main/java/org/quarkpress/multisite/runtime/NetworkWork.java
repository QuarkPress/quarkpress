package org.quarkpress.multisite.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.transaction.Status;
import java.util.concurrent.Callable;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.runtime.NetworkLockRepository;

/** Runs a network service's work in the network scope and rethrows its unchecked failures unchanged. */
final class NetworkWork {

    private NetworkWork() {}

    /**
     * Refuses to start a network write on a thread of its own while the caller's transaction holds the network lock
     * (the write would wait for the lock, the caller for the write). A caller bound to the network joins its own
     * transaction instead, so it may hold the lock while that transaction is active (not when it is marked for
     * rollback, which the caller's own write would then fail on anyway).
     */
    private static void requireLockNotHeld() {
        if (!Arc.container().instance(NetworkLockRepository.class).get().heldByCurrentTransaction()) {
            return;
        }
        if (QuarkusTransaction.getStatus() == Status.STATUS_ACTIVE
                && Arc.container().requestContext().isActive()
                && Arc.container()
                        .instance(SiteBinding.class)
                        .get()
                        .bound()
                        .filter(binding -> binding.kind() == Binding.Kind.NETWORK)
                        .isPresent()) {
            return;
        }
        throw new IllegalStateException(NetworkLockRepository.HELD_MESSAGE);
    }

    static <T> T call(NetworkScope network, Callable<T> work) {
        requireLockNotHeld();
        try {
            return network.call(work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
