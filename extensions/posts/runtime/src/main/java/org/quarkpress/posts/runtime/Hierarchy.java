package org.quarkpress.posts.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.quarkpress.core.PostId;

/** Parent chains as WordPress walks them: the loop check of a save, and a post's ancestors. */
public final class Hierarchy {

    private Hierarchy() {}

    /** The parent a saved post gets, and the members of a loop further up whose parents must be cleared. */
    public record Decision(Optional<PostId> parent, List<PostId> cut) {
        public Decision {
            Objects.requireNonNull(parent, "parent");
            cut = List.copyOf(cut);
        }
    }

    /**
     * WordPress's loop check for {@code post} given {@code parent}: none stays none; its own id gives none; a chain
     * that comes back to the post gives none; a loop further up keeps the parent and names the loop's members, in the
     * order WordPress clears them. {@code storedParents} answers the stored parent of every other post.
     */
    public static Decision check(
            PostId post, Optional<PostId> parent, Function<PostId, Optional<PostId>> storedParents) {
        if (parent.isEmpty()) {
            return new Decision(parent, List.of());
        }
        if (parent.get().equals(post)) {
            return new Decision(Optional.empty(), List.of());
        }
        Function<PostId, Optional<PostId>> next = id -> id.equals(post) ? parent : storedParents.apply(id);
        Optional<PostId> meeting = walk(post, next, id -> {});
        if (meeting.isEmpty()) {
            return new Decision(parent, List.of());
        }
        Set<PostId> loop = new LinkedHashSet<>();
        walk(meeting.get(), next, loop::add);
        if (loop.contains(post)) {
            return new Decision(Optional.empty(), List.of());
        }
        return new Decision(parent, new ArrayList<>(loop));
    }

    /**
     * Walks up from {@code start} with one walker taking one step and another taking two steps at a time, as WordPress
     * does: the slow walker's post once either of the fast one's two steps lands on it, or empty when the chain ends.
     * Each round gives {@code visited} the posts the walkers stand on: the slow one's, then the fast one's two steps.
     */
    private static Optional<PostId> walk(
            PostId start, Function<PostId, Optional<PostId>> next, Consumer<PostId> visited) {
        PostId slow = start;
        PostId fast = start;
        while (true) {
            Optional<PostId> between = next.apply(fast);
            if (between.isEmpty()) {
                return Optional.empty();
            }
            Optional<PostId> ahead = next.apply(between.get());
            if (ahead.isEmpty()) {
                return Optional.empty();
            }
            fast = ahead.get();
            visited.accept(slow);
            visited.accept(between.get());
            visited.accept(fast);
            if (slow.equals(between.get()) || slow.equals(fast)) {
                return Optional.of(slow);
            }
            Optional<PostId> step = next.apply(slow);
            if (step.isEmpty()) {
                return Optional.empty();
            }
            slow = step.get();
        }
    }

    /**
     * WordPress's ancestors of {@code post} whose parent is {@code parent}, nearest first: the walk stops at a post
     * without parent, at a missing post, at the post itself, or at a post already listed.
     */
    public static List<PostId> ancestors(
            PostId post, Optional<PostId> parent, Function<PostId, Optional<PostId>> storedParents) {
        List<PostId> ancestors = new ArrayList<>();
        if (parent.isEmpty() || parent.get().equals(post)) {
            return ancestors;
        }
        Set<PostId> listed = new HashSet<>();
        PostId current = parent.get();
        ancestors.add(current);
        listed.add(current);
        while (true) {
            Optional<PostId> next = storedParents.apply(current);
            if (next.isEmpty() || next.get().equals(post) || listed.contains(next.get())) {
                return ancestors;
            }
            current = next.get();
            ancestors.add(current);
            listed.add(current);
        }
    }
}
