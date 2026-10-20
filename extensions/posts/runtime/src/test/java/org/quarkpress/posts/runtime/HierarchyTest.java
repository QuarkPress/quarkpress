package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;

class HierarchyTest {

    static PostId id(long value) {
        return PostId.of(value);
    }

    /** Stored parents: child id to parent id. */
    static Map<PostId, PostId> parents(long... pairs) {
        Map<PostId, PostId> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(id(pairs[i]), id(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void noParentAndOwnParentGiveNone() {
        assertThat(Hierarchy.check(id(1), Optional.empty(), p -> Optional.empty())
                        .parent())
                .isEmpty();
        assertThat(Hierarchy.check(id(2), Optional.of(id(2)), p -> Optional.empty())
                        .parent())
                .isEmpty();
    }

    @Test
    void aParentThatClosesALoopThroughThePostLeavesItTopLevel() {
        Map<PostId, PostId> stored = parents(2, 1, 3, 2);
        Hierarchy.Decision decision =
                Hierarchy.check(id(1), Optional.of(id(3)), p -> Optional.ofNullable(stored.get(p)));
        assertThat(decision.parent()).isEmpty();
        assertThat(decision.cut()).isEmpty();
    }

    @Test
    void aLoopFurtherUpIsCutAndTheParentKept() {
        Map<PostId, PostId> stored = parents(4, 5, 5, 4);
        Hierarchy.Decision decision =
                Hierarchy.check(id(6), Optional.of(id(4)), p -> Optional.ofNullable(stored.get(p)));
        assertThat(decision.parent()).contains(id(4));
        assertThat(decision.cut()).containsExactlyInAnyOrder(id(4), id(5));
    }

    @Test
    void aChainWithoutLoopKeepsTheParent() {
        Map<PostId, PostId> stored = parents(2, 1);
        Hierarchy.Decision decision =
                Hierarchy.check(id(3), Optional.of(id(2)), p -> Optional.ofNullable(stored.get(p)));
        assertThat(decision.parent()).contains(id(2));
        assertThat(decision.cut()).isEmpty();
    }

    @Test
    void ancestorsStopAtTheTopAtAMissingPostOrAtARepeat() {
        Map<PostId, PostId> stored = parents(3, 2, 2, 1);
        assertThat(Hierarchy.ancestors(id(4), Optional.of(id(3)), p -> Optional.ofNullable(stored.get(p))))
                .containsExactly(id(3), id(2), id(1));
        assertThat(Hierarchy.ancestors(id(4), Optional.of(id(4)), p -> Optional.empty()))
                .isEmpty();
        Map<PostId, PostId> loop = parents(7, 8, 8, 7);
        assertThat(Hierarchy.ancestors(id(9), Optional.of(id(7)), p -> Optional.ofNullable(loop.get(p))))
                .containsExactly(id(7), id(8));
        Map<PostId, PostId> back = parents(11, 10);
        assertThat(Hierarchy.ancestors(id(10), Optional.of(id(11)), p -> Optional.ofNullable(back.get(p))))
                .containsExactly(id(11));
    }

    private static Hierarchy.Decision check(long post, long parent, Map<PostId, PostId> stored) {
        return Hierarchy.check(id(post), Optional.of(id(parent)), p -> Optional.ofNullable(stored.get(p)));
    }

    @Test
    void postsBetweenTheParentAndTheLoopAreNotCut() {
        Hierarchy.Decision decision = check(6, 4, parents(4, 5, 5, 7, 7, 5));
        assertThat(decision.parent()).contains(id(4));
        assertThat(decision.cut()).containsExactly(id(5), id(7));
    }

    @Test
    void aParentStoredAsItsOwnParentIsCut() {
        Hierarchy.Decision decision = check(6, 4, parents(4, 4));
        assertThat(decision.parent()).contains(id(4));
        assertThat(decision.cut()).containsExactly(id(4));
    }

    @Test
    void membersAreCutFromWherePairedWalkersMeet() {
        assertThat(check(6, 4, parents(4, 5, 5, 4)).cut()).containsExactly(id(4), id(5));
        assertThat(check(1, 200, parents(200, 201, 201, 202, 202, 203, 203, 200))
                        .cut())
                .containsExactly(id(201), id(202), id(203), id(200));
        assertThat(check(1, 10, parents(10, 11, 11, 20, 20, 21, 21, 22, 22, 20)).cut())
                .containsExactly(id(21), id(22), id(20));
    }

    @Test
    void aPostStoredInALoopOrAsItsOwnParentLosesItsParentOnAnySave() {
        assertThat(check(8, 9, parents(9, 8)).parent()).isEmpty();
        assertThat(check(8, 9, parents(9, 8)).cut()).isEmpty();
        assertThat(check(5, 5, parents(5, 5)).parent()).isEmpty();
    }

    @Test
    void aThreePostLoopAfterAOnePostTailIsCutInWordPressOrder() {
        assertThat(check(1, 200, parents(200, 201, 201, 202, 202, 200)).cut())
                .containsExactly(id(200), id(201), id(202));
        assertThat(check(1, 100, parents(100, 200, 200, 201, 201, 202, 202, 200))
                        .cut())
                .containsExactly(id(200), id(201), id(202));
    }
}
