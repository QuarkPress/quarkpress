package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class FeatureDescriptorTest {

    @ParameterizedTest
    @ValueSource(strings = {"revisions", "scheduling", "a", "rest-wp-v2", "x_1"})
    void acceptsLowerCaseKeys(String key) {
        assertThat(FeatureDescriptor.isValidKey(key)).isTrue();
    }

    static Stream<String> otherKeys() {
        // Built from a code point: the formatter turns printable escapes into literal characters.
        String eAcute = Character.toString(0xE9);
        return Stream.of("", "Revisions", "1abc", "-a", "a b", "a.b", "caf" + eAcute);
    }

    @ParameterizedTest
    @MethodSource("otherKeys")
    void rejectsOtherKeys(String key) {
        assertThat(FeatureDescriptor.isValidKey(key)).isFalse();
        assertThatThrownBy(() -> new FeatureDescriptor(key, "m", true, "d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid feature key '" + key
                        + "': use 1 to 64 characters a-z, 0-9, '-' or '_', starting with a letter");
    }

    @Test
    void nullPartsAreRejected() {
        assertThat(FeatureDescriptor.isValidKey(null)).isFalse();
        assertThatThrownBy(() -> new FeatureDescriptor(null, "m", true, "d"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");
        assertThatThrownBy(() -> new FeatureDescriptor("k", null, true, "d"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("module");
        assertThatThrownBy(() -> new FeatureDescriptor("k", "m", true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("description");
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
        int tries = 3000;
        AtomicInteger accepted = new AtomicInteger();
        Properties.check(
                "FeatureDescriptorTest.anyText",
                tries,
                Gens.oneOf(Gens.strings(Gens.unicodeChars(), 0, 70), Gens.strings("abz09-_A ", 0, 70), mutatedKeys()),
                text -> {
                    boolean valid = FeatureDescriptor.isValidKey(text);
                    assertThat(valid).isEqualTo(followsTheRule(text));
                    if (valid) {
                        accepted.incrementAndGet();
                    }
                });
        // The mutated keys keep both answers frequent, so the property checks the accept path too.
        assertThat(accepted.get()).isBetween(tries / 20, tries - tries / 20);
    }

    /** Valid keys (lengths 1 to 66, around the limit) with at most one character replaced, inserted or removed. */
    private static Gen<String> mutatedKeys() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-_";
        String noise = alphabet + "A.Z ";
        Gen<String> keys = Gens.ints(0, 65)
                .flatMap(length -> Gens.strings(alphabet, length, length).map(rest -> "k" + rest));
        return random -> {
            StringBuilder key = new StringBuilder(keys.next(random));
            int at = random.nextInt(key.length() + 1);
            char c = noise.charAt(random.nextInt(noise.length()));
            switch (random.nextInt(4)) {
                case 0 -> {}
                case 1 -> key.insert(at, c);
                case 2 -> key.setCharAt(Math.min(at, key.length() - 1), c);
                default -> key.deleteCharAt(Math.min(at, key.length() - 1));
            }
            return key.toString();
        };
    }

    @Test
    void keysBuiltFromTheAlphabetAreAccepted() {
        int tries = 2000;
        AtomicInteger accepted = new AtomicInteger();
        Properties.check(
                "FeatureDescriptorTest.valid",
                tries,
                Gens.strings("abcdefghijklmnopqrstuvwxyz0123456789-_", 0, 63).map(rest -> "k" + rest),
                key -> {
                    assertThat(new FeatureDescriptor(key, "m", true, "d").key()).isEqualTo(key);
                    accepted.incrementAndGet();
                });
        assertThat(accepted.get()).isEqualTo(tries);
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
