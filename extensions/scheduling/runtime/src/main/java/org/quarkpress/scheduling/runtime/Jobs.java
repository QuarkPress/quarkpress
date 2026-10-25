package org.quarkpress.scheduling.runtime;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Runs a scheduling job on a thread of its own, with nothing of the caller's context: no request context, no
 * transaction, no security identity, no scope binding. A job then opens its own scopes and transactions, the same
 * way whichever thread called it (the scheduler's, a test's, the Dev UI's). The thread is a fresh virtual thread
 * started without context propagation and without the caller's inheritable thread-locals.
 * <p>
 * A job is refused inside a transaction. The detached thread carries none of the spawner's lock bookkeeping that a
 * site scope carries when it moves to a thread of its own: a job called from a transaction that holds the network
 * lock, or a site's record, would wait on that lock from its own thread, and no database would see the deadlock.
 */
public final class Jobs {

    private Jobs() {}

    /**
     * Runs {@code work} on a new virtual thread and returns its result. A {@code RuntimeException} or {@code Error}
     * the work threw is rethrown unchanged on the caller's thread; a checked exception is wrapped in an
     * {@code IllegalStateException}. If the caller is interrupted while waiting, its interruption is restored and
     * reported as an {@code IllegalStateException}; the worker is not interrupted and keeps running unattended until
     * its work ends.
     *
     * @throws IllegalStateException when called inside a transaction ({@code a scheduling job is not called inside
     *     a transaction}), when the work threw a checked exception, or when the wait was interrupted
     */
    public static <T> T detached(Callable<T> work) {
        Objects.requireNonNull(work, "work");
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException("a scheduling job is not called inside a transaction");
        }
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread worker = Thread.ofVirtual()
                .name("quarkpress-job")
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    try {
                        result[0] = work.call();
                    } catch (Throwable thrown) {
                        failure[0] = thrown;
                    }
                });
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a scheduling job", interrupted);
        }
        if (failure[0] instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }
}
