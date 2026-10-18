package org.quarkpress.multisite.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Runs test code inside or outside a CDI request context. Same as the persistence deployment test helper of that name. */
public final class Requests {

    private Requests() {}

    /** Runs {@code work} in the current request context, activating one only when none is active. */
    public static <T> T call(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return work.get();
        }
        requestContext.activate();
        try {
            return work.get();
        } finally {
            requestContext.terminate();
        }
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} on a new thread inside a new request context, so no request state is shared. */
    public static void fresh(Runnable work) {
        onNewThread(() -> run(work));
    }

    /** Runs {@code work} on a new thread without any request context. */
    public static void outside(Runnable work) {
        onNewThread(work);
    }

    /**
     * Runs {@code work} on a new thread that shares the current request context (as another thread serving the same
     * request would) and returns its result. The caller's thread, and its transaction, are left as they are.
     */
    public static <T> T onSharedContext(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        InjectableContext.ContextState state = requestContext.getState();
        AtomicReference<T> result = new AtomicReference<>();
        onNewThread(() -> {
            requestContext.activate(state);
            try {
                result.set(work.get());
            } finally {
                requestContext.deactivate();
            }
        });
        return result.get();
    }

    private static void onNewThread(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        Throwable thrown = failure.get();
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new IllegalStateException(thrown);
        }
    }
}
