package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntityNormalizerTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("normalize.json");
    }

    @Test
    void validCodePointsFollowValidUnicode() {
        assertThat(EntityNormalizer.isValidCodePoint(0x9)).isTrue();
        assertThat(EntityNormalizer.isValidCodePoint(0x8)).isFalse();
        assertThat(EntityNormalizer.isValidCodePoint(0xFFFE)).isFalse();
        assertThat(EntityNormalizer.isValidCodePoint(0x10FFFF)).isTrue();
        assertThat(EntityNormalizer.isValidCodePoint(0x110000)).isFalse();
    }

    @Test
    void floodsAreLinear() {
        assertThat(EntityNormalizer.normalize("&".repeat(1_000_000), false)).hasSize(5_000_000);
        assertThat(EntityNormalizer.normalize("&amp;".repeat(200_000), false)).isEqualTo("&amp;".repeat(200_000));
    }
}
