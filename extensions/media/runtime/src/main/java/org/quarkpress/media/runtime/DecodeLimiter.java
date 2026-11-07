package org.quarkpress.media.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many images are decoded at once, so that concurrent uploads cannot exhaust the memory together: a
 * processing run holds a permit from its decode to its last size. Permits are handed out in arrival order; a run waits
 * for one, it never fails for want of one.
 */
@ApplicationScoped
public class DecodeLimiter {

    @Inject
    MediaConfig config;

    private Semaphore permits;

    @PostConstruct
    void create() {
        permits = new Semaphore(limit(config), true);
    }

    /** The configured number of concurrent decodes, or half the available processors; at least one. */
    static int limit(MediaConfig config) {
        int configured =
                config.maxConcurrentDecodes().orElse(Runtime.getRuntime().availableProcessors() / 2);
        return Math.max(1, configured);
    }

    /** Waits for a permit; the caller closes the returned permit exactly once, when its last size is made. */
    public Permit acquire() {
        permits.acquireUninterruptibly();
        return new Permit(permits);
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
