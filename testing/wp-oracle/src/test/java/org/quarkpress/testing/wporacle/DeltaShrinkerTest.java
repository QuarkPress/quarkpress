package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeltaShrinkerTest {

    @Test
    void shrinksToAMinimalFailingInput() {
        AtomicInteger rounds = new AtomicInteger();
        String result = DeltaShrinker.shrink(
                "aaaXbbbYccc",
                candidates -> {
                    rounds.incrementAndGet();
                    return candidates.stream()
                            .map(c -> c.contains("X") && c.contains("Y"))
                            .toList();
                },
                50);
        assertThat(result).isEqualTo("XY");
        assertThat(rounds.get()).isLessThanOrEqualTo(50);
    }

    @Test
    void stopsAfterMaxRounds() {
        String result = DeltaShrinker.shrink(
                "abcdef", candidates -> candidates.stream().map(c -> true).toList(), 1);
        assertThat(result).isEqualTo("def");
    }

    @Test
    void keepsTheInputWhenNothingSmallerFails() {
        assertThat(DeltaShrinker.shrink(
                        "xy", candidates -> candidates.stream().map(c -> false).toList(), 10))
                .isEqualTo("xy");
    }
}
