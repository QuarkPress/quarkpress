package org.quarkpress.persistence.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.transaction.Status;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.NetworkScope;

/**
 * Runs work under a binding without changing any binding other code can see.
 *
 * <p>When the caller's request is already bound to the target, the work runs in the caller's request context and
 * joins its transaction if one is active. Otherwise (another binding, an unbound request or no request context) the
 * work runs on a new virtual thread of its own, with no context propagated to it: that thread activates a fresh
 * request context bound to the target, runs the work in its own transaction and terminates the context, while the
 * caller waits. The caller's request context is never deactivated or changed, so code sharing it (other threads on
 * the same Vert.x context, tasks that captured it) keeps seeing the caller's site. A scope for another binding
 * cannot be opened on a Vert.x event-loop thread, which must not block.
 *
 * <p>Whatever the work throws (exception or error) is rethrown unchanged on the caller's thread.
 */
public final class Scopes {

    private static final boolean VERTX_PRESENT = present("io.vertx.core.Context");

    private Scopes() {}

    /** Runs the work for one site; {@code target} must be a site binding (the network opens through NetworkScope). */
    public static <T> T within(Binding target, Callable<T> work) throws Exception {
        Objects.requireNonNull(target, "target");
        if (target.kind() != Binding.Kind.SITE) {
            throw new IllegalArgumentException(
                    "a scope binds one site; the network scope opens only through " + NetworkScope.class.getName());
        }
        return run(target, work);
    }

    /** Runs the work for the whole network; only {@link NetworkScope} holds a key. */
    public static <T> T withinNetwork(NetworkScope.Key key, Callable<T> work) throws Exception {
        Objects.requireNonNull(key, "the network scope opens only through " + NetworkScope.class.getName());
        return run(Binding.NETWORK, work);
    }

    private static <T> T run(Binding target, Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()
                && Arc.container()
                        .instance(SiteBinding.class)
                        .get()
                        .bound()
                        .filter(target::equals)
                        .isPresent()) {
            boolean join = QuarkusTransaction.getStatus() == Status.STATUS_ACTIVE;
            return inTransaction(join ? QuarkusTransaction.joiningExisting() : QuarkusTransaction.requiringNew(), work);
        }
        if (VERTX_PRESENT && EventLoops.onEventLoop()) {
            throw new IllegalStateException("a site scope cannot run on an event-loop thread");
        }
        return onOwnThread(target, work);
    }

    private static <T> T onOwnThread(Binding target, Callable<T> work) throws Exception {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread worker = Thread.ofVirtual().name("quarkpress-scope").unstarted(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                Arc.container().instance(SiteBinding.class).get().bind(target);
                result[0] = inNewTransaction(work);
            } catch (Throwable thrown) {
                failure[0] = thrown;
            } finally {
                try {
                    requestContext.terminate();
                } catch (Throwable thrown) {
                    if (failure[0] == null) {
                        failure[0] = thrown;
                    } else {
                        failure[0].addSuppressed(thrown);
                    }
                }
            }
        });
        worker.start();
        awaitUninterruptibly(worker);
        Throwable thrown = failure[0];
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new IllegalStateException(thrown);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    /**
     * Waits for the work to finish even when the caller is interrupted (the work holds a transaction and must not be
     * abandoned); the interrupt is passed on to the work and kept on the caller.
     */
    private static void awaitUninterruptibly(Thread worker) {
        boolean interrupted = false;
        while (true) {
            try {
                worker.join();
                break;
            } catch (InterruptedException e) {
                if (!interrupted) {
                    worker.interrupt();
                }
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the work in a new transaction on a thread without one. The work's failure is rethrown as it is, with a
     * failed rollback added as suppressed; a failed commit is thrown as it comes.
     */
    private static <T> T inNewTransaction(Callable<T> work) throws Exception {
        QuarkusTransaction.begin();
        T value;
        try {
            value = work.call();
        } catch (Exception | Error thrown) {
            try {
                QuarkusTransaction.rollback();
            } catch (Throwable rollbackFailure) {
                thrown.addSuppressed(rollbackFailure);
            }
            throw thrown;
        }
        QuarkusTransaction.commit();
        return value;
    }

    /**
     * Runs the work in the caller's thread through the runner and rethrows exactly what the work threw (the runner
     * wraps checked exceptions, and may wrap others); failures of the transaction itself are rethrown as they come.
     */
    private static <T> T inTransaction(TransactionRunnerOptions runner, Callable<T> work) throws Exception {
        Throwable[] thrownByWork = new Throwable[1];
        try {
            return runner.call(() -> {
                try {
                    return work.call();
                } catch (Exception | Error thrown) {
                    thrownByWork[0] = thrown;
                    throw thrown;
                }
            });
        } catch (RuntimeException | Error failure) {
            Throwable original = thrownByWork[0];
            if (original instanceof Exception exception) {
                throw exception;
            }
            if (original instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Scopes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
