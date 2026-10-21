package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;

class TermHierarchyTest {

    private static TermId t(long id) {
        return TermId.of(id);
    }

    /** A (1) > B (2) > C (3). */
    private static final Map<TermId, TermId> PARENTS = Map.of(t(2), t(1), t(3), t(2));

    private static Optional<TermId> stored(TermId id) {
        return Optional.ofNullable(PARENTS.get(id));
    }

    @Test
    void aParentThatIsADescendantIsDropped() {
        assertThat(TermHierarchy.check(t(1), Optional.of(t(3)), TermHierarchyTest::stored))
                .isEqualTo(new TermHierarchy.Decision(Optional.empty(), List.of()));
        assertThat(TermHierarchy.check(t(2), Optional.of(t(2)), TermHierarchyTest::stored)
                        .parent())
                .isEmpty();
        assertThat(TermHierarchy.check(t(3), Optional.of(t(1)), TermHierarchyTest::stored))
                .isEqualTo(new TermHierarchy.Decision(Optional.of(t(1)), List.of()));
    }

    @Test
    void aLoopFurtherUpIsCutAndTheParentKept() {
        Map<TermId, TermId> loop = new HashMap<>(Map.of(t(5), t(6), t(6), t(5)));
        TermHierarchy.Decision decision =
                TermHierarchy.check(t(9), Optional.of(t(5)), id -> Optional.ofNullable(loop.get(id)));
        assertThat(decision.parent()).contains(t(5));
        assertThat(decision.cut()).containsExactly(t(5), t(6));
    }

    @Test
    void ancestorsRunFromTheParentToTheRoot() {
        assertThat(TermHierarchy.ancestors(t(3), TermHierarchyTest::stored)).containsExactly(t(2), t(1));
        assertThat(TermHierarchy.ancestors(t(1), TermHierarchyTest::stored)).isEmpty();
        Map<TermId, TermId> loop = Map.of(t(7), t(8), t(8), t(7));
        assertThat(TermHierarchy.ancestors(t(7), id -> Optional.ofNullable(loop.get(id))))
                .containsExactly(t(8), t(7));
    }

    @Test
    void childrenListDirectChildrenThenEachChildsDescendants() {
        // A (1) has B (2) and C (3); B has D (4); D has E (5); C has F (6)
        Map<TermId, List<TermId>> children = Map.of(
                t(1), List.of(t(2), t(3)),
                t(2), List.of(t(4)),
                t(4), List.of(t(5)),
                t(3), List.of(t(6)));
        assertThat(TermHierarchy.children(t(1), children)).containsExactly(t(2), t(3), t(4), t(5), t(6));
        assertThat(TermHierarchy.children(t(5), children)).isEmpty();
        Map<TermId, List<TermId>> loop = Map.of(t(1), List.of(t(2)), t(2), List.of(t(1)));
        assertThat(TermHierarchy.children(t(1), loop)).containsExactly(t(2));
        // a term stored as its own parent is listed among its own children, once
        Map<TermId, List<TermId>> own = Map.of(t(1), List.of(t(1), t(2)));
        assertThat(TermHierarchy.children(t(1), own)).containsExactly(t(1), t(2));
    }
}
