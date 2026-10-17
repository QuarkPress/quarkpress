package org.quarkpress.testing.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PropertiesTest {

    @Test
    void runsThePropertyOncePerTry() {
        List<Integer> seen = new ArrayList<>();
        Properties.check("PropertiesTest.count", 37, Gens.ints(0, 9), seen::add);
        assertThat(seen).hasSize(37);
    }

    @Test
    void theSameNameGivesTheSameInputs() {
        List<Integer> first = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        Properties.check("PropertiesTest.same", 50, Gens.ints(0, 1_000_000), first::add);
        Properties.check("PropertiesTest.same", 50, Gens.ints(0, 1_000_000), second::add);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anExplicitSeedIsUsed() {
        List<Integer> first = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        Properties.check("a", 50, 99L, Gens.ints(0, 1_000_000), first::add);
        Properties.check("b", 50, 99L, Gens.ints(0, 1_000_000), second::add);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aFailureNamesThePropertySeedTryAndInput() {
        assertThatThrownBy(() -> Properties.check("PropertiesTest.fails", 100, 4242L, Gens.ints(5, 5), v -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PropertiesTest.fails")
                .hasMessageContaining("-DquarkpressGenSeed=4242")
                .hasMessageContaining("try 1 of 100")
                .hasMessageContaining("input: 5")
                .hasMessageContaining("boom")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGeneratorFailureNamesThePropertySeedAndTry() {
        Gen<Integer> broken = random -> {
            throw new IllegalStateException("bad generator");
        };
        assertThatThrownBy(() -> Properties.check("PropertiesTest.broken", 10, 7L, broken, v -> {}))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PropertiesTest.broken")
                .hasMessageContaining("-DquarkpressGenSeed=7")
                .hasMessageContaining("try 1 of 10")
                .hasMessageContaining("bad generator");
    }

    @Test
    void aMalformedSeedPropertyFailsNamingTheProperty() {
        String before = System.getProperty(Properties.SEED_PROPERTY);
        System.setProperty(Properties.SEED_PROPERTY, "12x");
        try {
            assertThatThrownBy(() -> Properties.check("PropertiesTest.badSeed", 1, Gens.constant(1), v -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PropertiesTest.badSeed")
                    .hasMessageContaining("-DquarkpressGenSeed=12x");
        } finally {
            if (before == null) {
                System.clearProperty(Properties.SEED_PROPERTY);
            } else {
                System.setProperty(Properties.SEED_PROPERTY, before);
            }
        }
    }

    @Test
    void longInputsAreTruncatedInTheMessage() {
        assertThatThrownBy(() -> Properties.check("PropertiesTest.long", 1, 1L, Gens.strings("x", 5000, 5000), v -> {
                    throw new AssertionError("no");
                }))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(2300));
    }

    @Test
    void byteArraysArePrintedAsContent() {
        assertThatThrownBy(
                        () -> Properties.check("PropertiesTest.bytes", 1, 1L, Gens.constant(new byte[] {1, 2}), v -> {
                            throw new AssertionError("no");
                        }))
                .hasMessageContaining("input: [1, 2]");
    }

    @Test
    void seedsFromTextAreStable() {
        assertThat(Properties.seedOf("20261014")).isEqualTo(Properties.seedOf("20261014"));
        assertThat(Properties.seedOf("20261014")).isNotEqualTo(Properties.seedOf("20261015"));
        // first eight bytes of SHA-256 of the empty string, big-endian
        assertThat(Properties.seedOf("")).isEqualTo(0xe3b0c44298fc1c14L);
    }
}
