package org.quarkpress.revisions.runtime;

import java.util.function.Supplier;

/**
 * Marks the thread while the module autosaves a draft through the posts module, so the observer of post saves creates
 * no revision for that save (WordPress's {@code DOING_AUTOSAVE}). The mark is a depth counter on the thread: the own
 * draft's update runs on the caller's thread inside the caller's transaction.
 */
final class Autosaving {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private Autosaving() {}

    static <T> T during(Supplier<T> work) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            return work.get();
        } finally {
            int depth = DEPTH.get() - 1;
            if (depth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }

    static boolean active() {
        return DEPTH.get() > 0;
    }
}
