package org.quarkpress.persistence.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Runs test code inside or outside a CDI request context. */
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
