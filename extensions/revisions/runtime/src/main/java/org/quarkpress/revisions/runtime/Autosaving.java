package org.quarkpress.revisions.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.quarkpress.core.PostId;

/**
 * Marks the thread while the module autosaves a draft through the posts module, so the observer of post saves creates
 * no revision for that save (WordPress's {@code DOING_AUTOSAVE}), and while a caller defers one post's revision to
 * the end of its work ({@link #silencing}), so the observer skips that post's saves meanwhile. Both marks are depth
 * counters on the thread: the work runs on the caller's thread inside the caller's transaction, and calls nest.
 */
final class Autosaving {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Map<Long, Integer>> SILENCED = ThreadLocal.withInitial(HashMap::new);

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

    /** Runs the work with the post's saves silenced for the observer. */
    static <T> T silencing(PostId post, Supplier<T> work) {
        Map<Long, Integer> silenced = SILENCED.get();
        silenced.merge(post.value(), 1, Integer::sum);
        try {
            return work.get();
        } finally {
            int depth = silenced.get(post.value()) - 1;
            if (depth == 0) {
                silenced.remove(post.value());
            } else {
                silenced.put(post.value(), depth);
            }
            if (silenced.isEmpty()) {
                SILENCED.remove();
            }
        }
    }

    /** Whether a caller on this thread defers the post's revision. */
    static boolean silenced(PostId post) {
        return SILENCED.get().containsKey(post.value());
    }
}
