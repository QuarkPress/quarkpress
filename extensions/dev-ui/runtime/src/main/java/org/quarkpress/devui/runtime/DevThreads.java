package org.quarkpress.devui.runtime;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Runs a Dev UI call on a thread of its own, with nothing of the caller's context: no request context, no
 * transaction, no security identity, no scope binding. The work then opens its own scopes, the same way whichever
 * thread Dev UI called it on. The thread is a fresh virtual thread started without context propagation and without
 * the caller's inheritable thread-locals; it keeps the caller's context class loader, which a thread without inherited
 * thread-locals would otherwise lose (it would get the system class loader, under which the application's beans
 * cannot be looked up).
 *
 * <p>A call is refused inside a transaction: the detached thread carries none of the caller's lock bookkeeping, so work
 * waiting there on a lock the caller's transaction holds would deadlock unseen.
 *
 * <p>This mirrors the scheduling module's job runner on purpose: dev-ui must not depend on scheduling, which is
 * optional.
 */
public final class DevThreads {

    /** Whether the application has transactions at all (the persistence module brings them). */
    private static final boolean TRANSACTIONS = present("io.quarkus.narayana.jta.QuarkusTransaction");

    private DevThreads() {}

    /**
     * Runs {@code work} on a new virtual thread named {@code quarkpress-dev-ui} and returns its result. A
     * {@code RuntimeException} or {@code Error} the work threw is rethrown unchanged on the caller's thread; a checked
     * exception is wrapped in an {@code IllegalStateException}. If the caller is interrupted while waiting, its
     * interruption is restored and reported as an {@code IllegalStateException}; the worker is not interrupted and
     * keeps running unattended until its work ends.
     *
     * @throws IllegalStateException when called inside a transaction ({@code a Dev UI call is not made inside a
     *     transaction}), when the work threw a checked exception, or when the wait was interrupted
     */
    public static <T> T detached(Callable<T> work) {
        Objects.requireNonNull(work, "work");
        if (TRANSACTIONS && Transactions.active()) {
            throw new IllegalStateException("a Dev UI call is not made inside a transaction");
        }
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread worker = Thread.ofVirtual()
                .name("quarkpress-dev-ui")
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    try {
                        result[0] = work.call();
                    } catch (Throwable thrown) {
                        failure[0] = thrown;
                    }
                });
        worker.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a Dev UI call", interrupted);
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

    private static boolean present(String className) {
        try {
            Class.forName(className, false, DevThreads.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Loaded only when the application has transactions, so the class is never resolved without them. */
    private static final class Transactions {

        private Transactions() {}

        static boolean active() {
            return QuarkusTransaction.isActive();
        }
    }
}
