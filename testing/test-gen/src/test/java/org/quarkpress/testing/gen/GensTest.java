package org.quarkpress.testing.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class GensTest {

    private static <T> List<T> draw(Gen<T> gen, long seed, int count) {
        RandomGenerator random = new SplittableRandom(seed);
        List<T> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(gen.next(random));
        }
        return out;
    }

    @Test
    void theSameSeedGivesTheSameSequence() {
        Gen<List<Integer>> gen = Gens.lists(Gens.ints(-50, 50), 0, 8);
        assertThat(draw(gen, 7L, 200)).isEqualTo(draw(gen, 7L, 200));
    }

    @Test
    void differentSeedsGiveDifferentSequences() {
        Gen<Long> gen = Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(draw(gen, 7L, 50)).isNotEqualTo(draw(gen, 8L, 50));
    }

    @Test
    void intsStayInRangeAndHitBothBounds() {
        List<Integer> values = draw(Gens.ints(-3, 1_000_000), 1L, 1000);
        assertThat(values).allSatisfy(v -> assertThat(v).isBetween(-3, 1_000_000));
        assertThat(values).contains(-3, 1_000_000, 0);
    }

    @Test
    void longsCoverTheExtremesOfTheFullRange() {
        List<Long> values = draw(Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE), 2L, 1000);
        assertThat(values).contains(Long.MIN_VALUE, Long.MAX_VALUE, 0L);
    }

    private static int digits(long value) {
        return Long.toString(Math.abs(value)).length();
    }

    @Test
    void fullRangeLongsIncludeSmallMultiDigitValues() {
        Set<Integer> digitCounts = new HashSet<>();
        draw(Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE), 15L, 1000).stream()
                .filter(v -> v != Long.MIN_VALUE)
                .forEach(v -> digitCounts.add(digits(v)));
        assertThat(digitCounts).contains(1, 2, 3, 4, 5, 6, 10, 15, 19);
    }

    @Test
    void fullRangeIntsIncludeSmallMultiDigitValues() {
        Set<Integer> digitCounts = new HashSet<>();
        draw(Gens.ints(Integer.MIN_VALUE, Integer.MAX_VALUE), 16L, 1000).forEach(v -> digitCounts.add(digits(v)));
        assertThat(digitCounts).contains(1, 2, 3, 4, 5, 6, 10);
    }

    @Test
    void boundedDoublesIncludeShortDecimals() {
        List<Double> values = draw(Gens.doubles(-1e9, 1e9), 17L, 1000);
        assertThat(values)
                .anyMatch(d -> Math.abs(d) < 1000 && d != Math.rint(d) && Math.rint(d * 100) == d * 100)
                .anyMatch(d -> Math.abs(d) >= 2 && Math.abs(d) < 1000 && d == Math.rint(d));
    }

    @Test
    void boundedDoublesRejectARangeWiderThanTheLargestDouble() {
        assertThatThrownBy(() -> Gens.doubles(-Double.MAX_VALUE, Double.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doublesIncludeSpecialValues() {
        List<Double> values = draw(Gens.doubles(), 3L, 5000);
        assertThat(values).contains(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertThat(values).contains(Double.MAX_VALUE, -Double.MIN_VALUE);
    }

    @Test
    void boundedDoublesStayInRange() {
        assertThat(draw(Gens.doubles(-5.0, 5.0), 4L, 1000))
                .allSatisfy(d -> assertThat(d).isBetween(-5.0, 5.0));
    }

    @Test
    void listsHitTheMinimumAndMaximumSize() {
        Set<Integer> sizes = new HashSet<>();
        draw(Gens.lists(Gens.booleans(), 2, 9), 5L, 1000).forEach(l -> sizes.add(l.size()));
        assertThat(sizes).contains(2, 9).allSatisfy(s -> assertThat(s).isBetween(2, 9));
    }

    @Test
    void stringsUseOnlyTheAlphabetAndIncludeTheEmptyString() {
        List<String> values = draw(Gens.strings("ab", 0, 4), 6L, 1000);
        assertThat(values).contains("").allSatisfy(s -> assertThat(s).matches("[ab]{0,4}"));
    }

    @Test
    void unicodeCharsAvoidSurrogatesAndCoverAsciiControlAndOtherCharacters() {
        List<Character> values = draw(Gens.unicodeChars(), 14L, 3000);
        assertThat(values).noneMatch(Character::isSurrogate);
        assertThat(values)
                .anyMatch(c -> c < ' ')
                .anyMatch(c -> c >= ' ' && c <= '~')
                .anyMatch(c -> c > 0x7f);
    }

    @Test
    void bytesHaveTheRequestedLengths() {
        assertThat(draw(Gens.bytes(1, 3), 7L, 200))
                .allSatisfy(b -> assertThat(b.length).isBetween(1, 3));
    }

    @Test
    void elementsAndOneOfDrawEveryChoice() {
        assertThat(new HashSet<>(draw(Gens.elements("x", "y", "z"), 8L, 300))).containsExactlyInAnyOrder("x", "y", "z");
        Gen<Object> mixed = Gens.oneOf(Gens.constant("s"), Gens.constant(1));
        assertThat(new HashSet<>(draw(mixed, 9L, 100))).containsExactlyInAnyOrder("s", 1);
    }

    @Test
    void frequencyFollowsTheWeights() {
        Gen<String> gen =
                Gens.frequency(Gens.weighted(9, Gens.constant("often")), Gens.weighted(1, Gens.constant("rare")));
        List<String> values = draw(gen, 10L, 2000);
        long rare = values.stream().filter("rare"::equals).count();
        assertThat(rare).isBetween(100L, 300L);
    }

    @Test
    void filterGivesUpWithIllegalStateException() {
        Gen<Integer> never = Gens.ints(0, 10).filter(i -> i > 10);
        assertThatThrownBy(() -> never.next(new SplittableRandom(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter");
    }

    @Test
    void mapAndFlatMapCompose() {
        Gen<String> gen = Gens.ints(1, 3).flatMap(n -> Gens.strings("q", n, n)).map(s -> s + "!");
        assertThat(draw(gen, 11L, 100)).allSatisfy(s -> assertThat(s).matches("q{1,3}!"));
    }

    /** A tree is a leaf (an empty list) or a list of subtrees. */
    private static int depth(Object node) {
        int max = 0;
        for (Object child : (List<?>) node) {
            max = Math.max(max, 1 + depth(child));
        }
        return max;
    }

    @Test
    void recursiveRespectsMaxDepth() {
        Gen<Object> leaf = Gens.constant(List.of());
        Gen<Object> tree = Gens.recursive(leaf, sub -> Gens.lists(sub, 1, 3).map(l -> (Object) l), 4);
        List<Object> values = draw(tree, 12L, 300);
        assertThat(values).allSatisfy(t -> assertThat(depth(t)).isEqualTo(4));
        Gen<Object> mixed = Gens.recursive(
                leaf, sub -> Gens.oneOf(leaf, Gens.lists(sub, 1, 3).map(l -> (Object) l)), 3);
        List<Integer> depths =
                draw(mixed, 13L, 500).stream().map(GensTest::depth).toList();
        assertThat(depths).allSatisfy(d -> assertThat(d).isBetween(0, 3)).contains(0, 3);
    }
}
