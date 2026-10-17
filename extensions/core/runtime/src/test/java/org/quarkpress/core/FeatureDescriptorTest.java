package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class FeatureDescriptorTest {

    @ParameterizedTest
    @ValueSource(strings = {"revisions", "scheduling", "a", "rest-wp-v2", "x_1"})
    void acceptsLowerCaseKeys(String key) {
        assertThat(FeatureDescriptor.isValidKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Revisions", "1abc", "-a", "a b", "a.b", "café"})
    void rejectsOtherKeys(String key) {
        assertThat(FeatureDescriptor.isValidKey(key)).isFalse();
        assertThatThrownBy(() -> new FeatureDescriptor(key, "m", true, "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreAtMost64Characters() {
        assertThat(FeatureDescriptor.isValidKey("a".repeat(64))).isTrue();
        assertThat(FeatureDescriptor.isValidKey("a".repeat(65))).isFalse();
    }

    @Test
    void registryRejectsDuplicatesAndSortsByKey() {
        FeatureDescriptor b = new FeatureDescriptor("b", "m1", true, "B");
        FeatureDescriptor a = new FeatureDescriptor("a", "m2", false, "A");
        FeatureRegistry registry = new FeatureRegistry(List.of(b, a));
        assertThat(registry.all()).containsExactly(a, b);
        assertThat(registry.find("a")).contains(a);
        assertThat(registry.contains("zzz")).isFalse();
        assertThatThrownBy(() -> new FeatureRegistry(List.of(a, new FeatureDescriptor("a", "m3", true, "again"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("m2")
                .hasMessageContaining("m3");
    }

    @Test
    void validityMatchesTheRuleOnAnyText() {
        Properties.check(
                "FeatureDescriptorTest.anyText",
                3000,
                Gens.oneOf(Gens.strings(Gens.unicodeChars(), 0, 70), Gens.strings("abz09-_A ", 0, 70)),
                text -> assertThat(FeatureDescriptor.isValidKey(text)).isEqualTo(followsTheRule(text)));
    }

    @Test
    void keysBuiltFromTheAlphabetAreAccepted() {
        Properties.check(
                "FeatureDescriptorTest.valid",
                2000,
                Gens.strings("abcdefghijklmnopqrstuvwxyz0123456789-_", 0, 63).map(rest -> "k" + rest),
                key -> assertThat(new FeatureDescriptor(key, "m", true, "d").key())
                        .isEqualTo(key));
    }

    /** The key rule written another way: a lower-case ASCII letter, then up to 63 of a-z, 0-9, '-', '_'. */
    private static boolean followsTheRule(String text) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-_";
        return !text.isEmpty()
                && text.length() <= 64
                && text.charAt(0) >= 'a'
                && text.charAt(0) <= 'z'
                && text.chars().allMatch(c -> alphabet.indexOf(c) >= 0);
    }
}
