package org.quarkpress.media.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Bounds how many images are decoded at once, so that concurrent uploads cannot exhaust the memory together: a
 * processing run holds a permit from its decode to its last size. Permits are handed out in arrival order; a run waits
 * for one at most the configured decode wait, and fails when none came free by then.
 */
@ApplicationScoped
public class DecodeLimiter {

    private static final Logger LOG = Logger.getLogger(DecodeLimiter.class);

    @Inject
    MediaConfig config;

    private Semaphore permits;

    @PostConstruct
    void create() {
        int limit = limit(config);
        LOG.debugf("at most %d images are decoded at once", limit);
        permits = new Semaphore(limit, true);
    }

    /** The heap one decode is reckoned to take when the number of concurrent decodes is not configured. */
    static final long HEAP_PER_DECODE = 512L * 1024 * 1024;

    /** The configured number of concurrent decodes, or the default for this JVM (see {@link #defaultLimit}). */
    static int limit(MediaConfig config) {
        Runtime runtime = Runtime.getRuntime();
        return limit(config.maxConcurrentDecodes(), runtime.availableProcessors(), runtime.maxMemory());
    }

    /**
     * {@code configured} (at least one), else {@link #defaultLimit}.
     *
     * @param maxMemory the most heap the JVM uses, {@link Long#MAX_VALUE} when it has no limit
     */
    static int limit(Optional<Integer> configured, int processors, long maxMemory) {
        return configured.map(value -> Math.max(1, value)).orElseGet(() -> defaultLimit(processors, maxMemory));
    }

    /** Half the processors, at most one decode per 512 MiB of heap, at least one. */
    static int defaultLimit(int processors, long maxMemory) {
        long byHeap = maxMemory / HEAP_PER_DECODE;
        return (int) Math.max(1, Math.min(processors / 2, byHeap));
    }

    /**
     * Waits at most {@code wait} for a permit, in arrival order; the caller closes the returned permit exactly once,
     * when its last size is made. An interrupt does not end the wait early; it is kept for the caller.
     *
     * @throws DecodeWaitTimeout when no permit came free within {@code wait}
     */
    public Permit acquire(Duration wait) {
        long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    // a timed attempt keeps the arrival order, even with no time left
                    if (permits.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        return new Permit(permits);
                    }
                    throw new DecodeWaitTimeout(wait);
                } catch (InterruptedException interrupt) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** No decode permit came free within the wait. */
    public static final class DecodeWaitTimeout extends RuntimeException {

        private final Duration wait;

        DecodeWaitTimeout(Duration wait) {
            super("waited " + wait.toMillis() + " ms for a decode permit", null, false, false);
            this.wait = wait;
        }

        /** How long the run waited. */
        public Duration waited() {
            return wait;
        }
    }

    /** A held permit; closing it again does nothing. */
    public static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
