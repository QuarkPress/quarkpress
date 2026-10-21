package org.quarkpress.taxonomy.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.runtime.Hierarchy;

/** Parent chains of terms as WordPress walks them: the loop check of an update, ancestors and children. */
public final class TermHierarchy {

    /** The parent an updated term gets, and the members of a loop further up whose parents must be cleared. */
    public record Decision(Optional<TermId> parent, List<TermId> cut) {
        public Decision {
            Objects.requireNonNull(parent, "parent");
            cut = List.copyOf(cut);
        }
    }

    private TermHierarchy() {}

    /**
     * WordPress's loop check for {@code term} given {@code parent}, the same walk as for posts: none stays none; its
     * own id gives none; a chain that comes back to the term gives none; a loop further up keeps the parent and names
     * the loop's members in the order WordPress clears them. {@code storedParents} answers the stored parent of every
     * other term of the taxonomy.
     */
    public static Decision check(
            TermId term, Optional<TermId> parent, Function<TermId, Optional<TermId>> storedParents) {
        Hierarchy.Decision decision = Hierarchy.check(
                post(term),
                parent.map(TermHierarchy::post),
                id -> storedParents.apply(term(id)).map(TermHierarchy::post));
        return new Decision(
                decision.parent().map(TermHierarchy::term),
                decision.cut().stream().map(TermHierarchy::term).toList());
    }

    /**
     * WordPress's {@code get_ancestors} for a term of a hierarchical taxonomy: the stored parents from the term's own
     * up to the root, stopping at a missing term or at a parent already listed.
     */
    public static List<TermId> ancestors(TermId term, Function<TermId, Optional<TermId>> storedParents) {
        List<TermId> ancestors = new ArrayList<>();
        Optional<TermId> next = storedParents.apply(term);
        while (next.isPresent() && !ancestors.contains(next.get())) {
            ancestors.add(next.get());
            next = storedParents.apply(next.get());
        }
        return ancestors;
    }

    /**
     * WordPress's {@code get_term_children}: the term's direct children in the order of {@code childrenByParent}
     * (ascending id), then the descendants of each child in turn. A term that is its own child is listed
     * once and not descended into, as WordPress does; a term already listed is neither listed nor descended into
     * again.
     */
    public static List<TermId> children(TermId term, Map<TermId, List<TermId>> childrenByParent) {
        Set<TermId> seen = new HashSet<>();
        seen.add(term);
        return children(term, childrenByParent, seen);
    }

    private static List<TermId> children(TermId term, Map<TermId, List<TermId>> childrenByParent, Set<TermId> seen) {
        List<TermId> result = new ArrayList<>();
        List<TermId> direct = childrenByParent.getOrDefault(term, List.of());
        List<TermId> descend = new ArrayList<>();
        for (TermId child : direct) {
            if (child.equals(term)) {
                result.add(child);
            } else if (seen.add(child)) {
                result.add(child);
                descend.add(child);
            }
        }
        for (TermId child : descend) {
            result.addAll(children(child, childrenByParent, seen));
        }
        return result;
    }

    private static PostId post(TermId id) {
        return PostId.of(id.value());
    }

    private static TermId term(PostId id) {
        return TermId.of(id.value());
    }
}
