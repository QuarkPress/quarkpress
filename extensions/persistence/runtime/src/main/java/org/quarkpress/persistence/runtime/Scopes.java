package org.quarkpress.persistence.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.transaction.Status;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.quarkpress.core.SiteId;
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
 * the same Vert.x context, tasks that captured it) keeps seeing the caller's site. The check whether the caller's
 * request is bound reads the binding without asking the resolvers; it adopts a site an HTTP layer already decided for
 * the request, and the new thread's context is bound to the target without consulting that layer. A scope for
 * another binding cannot be opened on a Vert.x event-loop thread, which must not block.
 *
 * <p>Whatever the work throws (exception or error) is rethrown unchanged on the caller's thread.
 */
public final class Scopes {

    private static final boolean VERTX_PRESENT = present("io.vertx.core.Context");

    /** Set on a scope's own thread when the transaction of the thread that spawned it holds the network lock. */
    private static final ThreadLocal<Boolean> SPAWNER_HOLDS_LOCK = new ThreadLocal<>();

    /**
     * Set on a scope's own thread: the sites that the spawning threads up the chain were bound to while a transaction
     * of theirs was active. Rows such a transaction wrote lock the site's record until the spawner, which waits for
     * this thread, finishes.
     */
    private static final ThreadLocal<Set<SiteId>> SPAWNER_SITES = new ThreadLocal<>();

    private Scopes() {}

    /**
     * Whether this thread is a scope thread whose spawning thread's transaction holds the network lock: taking the
     * lock here would wait for the spawner, which waits for this thread, without any database seeing a deadlock.
     */
    static boolean spawnerHoldsNetworkLock() {
        return SPAWNER_HOLDS_LOCK.get() != null;
    }

    /**
     * Whether this thread is a scope thread and a thread up its spawn chain was bound to {@code site} while a
     * transaction of its own was active. That transaction may hold locks on the site's record (the foreign key checks
     * of rows it inserted), and it ends only after this thread's work: a change of the site's record from here would
     * wait for it without any database seeing a deadlock.
     */
    public static boolean spawnerBoundTo(SiteId site) {
        Set<SiteId> sites = SPAWNER_SITES.get();
        return sites != null && sites.contains(site);
    }

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
        boolean spawnerHolds =
                Arc.container().instance(NetworkLockRepository.class).get().heldByCurrentTransaction();
        Set<SiteId> spawnerSites = spawnerSites();
        Thread worker = Thread.ofVirtual().name("quarkpress-scope").unstarted(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            if (spawnerHolds) {
                SPAWNER_HOLDS_LOCK.set(Boolean.TRUE);
            }
            if (!spawnerSites.isEmpty()) {
                SPAWNER_SITES.set(spawnerSites);
            }
            try {
                Arc.container().instance(SiteBinding.class).get().bindScope(target);
                result[0] = inNewTransaction(work);
            } catch (Throwable thrown) {
                failure[0] = thrown;
            } finally {
                SPAWNER_HOLDS_LOCK.remove();
                SPAWNER_SITES.remove();
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
     * The sites a new scope thread must not change: those carried to this thread from up its spawn chain, and the site
     * this thread is bound to when a transaction of its own is active (rolled back or not, it holds its locks until it
     * ends). Read on the spawning thread, before the scope thread starts.
     */
    private static Set<SiteId> spawnerSites() {
        Set<SiteId> sites = new HashSet<>();
        Set<SiteId> carried = SPAWNER_SITES.get();
        if (carried != null) {
            sites.addAll(carried);
        }
        if (Arc.container().requestContext().isActive() && QuarkusTransaction.isActive()) {
            Optional<Binding> bound =
                    Arc.container().instance(SiteBinding.class).get().bound();
            if (bound.isPresent() && bound.get().kind() == Binding.Kind.SITE) {
                sites.add(bound.get().site());
            }
        }
        return Set.copyOf(sites);
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
