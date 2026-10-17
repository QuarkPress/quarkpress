package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class IdsTest {

    @Test
    void parsesCanonicalPositiveDecimals() {
        assertThat(SiteId.parse("1")).contains(SiteId.of(1));
        assertThat(SiteId.parse("42")).contains(SiteId.of(42));
        assertThat(PostId.parse("9223372036854775807")).contains(PostId.of(Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0",
                "-1",
                "+1",
                "01",
                " 1",
                "1 ",
                "1.0",
                "1e3",
                "0x1F",
                "9223372036854775808",
                "99999999999999999999",
                "\u0661",
                "\uff11"
            })
    void rejectsEverythingElse(String text) {
        assertThat(SiteId.parse(text)).isEmpty();
        assertThat(UserId.parse(text)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(TermId.parse(null)).isEmpty();
    }

    @Test
    void constructorsRejectZeroAndNegatives() {
        assertThatThrownBy(() -> SiteId.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RevisionId.of(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(Long.MIN_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsOfDifferentKindsAreNotEqual() {
        assertThat((Object) SiteId.of(7)).isNotEqualTo(PostId.of(7));
    }

    @Test
    void everyPositiveLongRoundTrips() {
        Properties.check(
                "IdsTest.roundTrip",
                2000,
                Gens.longs(1, Long.MAX_VALUE),
                value -> assertThat(SiteId.parse(Long.toString(value))).contains(SiteId.of(value)));
    }

    @Test
    void exactlyTheCanonicalTextsAreAccepted() {
        int tries = 5000;
        AtomicInteger accepted = new AtomicInteger();
        Properties.check(
                "IdsTest.canonical",
                tries,
                Gens.oneOf(
                        Gens.strings("0123456789+- ", 0, 21),
                        Gens.strings(Gens.unicodeChars(), 0, 21),
                        mutatedDecimals()),
                text -> {
                    Optional<SiteId> parsed = SiteId.parse(text);
                    assertThat(parsed.isPresent()).isEqualTo(isCanonicalPositiveLong(text));
                    parsed.ifPresent(id -> {
                        assertThat(Long.toString(id.value())).isEqualTo(text);
                        accepted.incrementAndGet();
                    });
                });
        // The mutated decimals keep both answers frequent, so the property checks the accept path too.
        assertThat(accepted.get()).isBetween(tries / 20, tries - tries / 20);
    }

    /**
     * Decimals around the long range (positive ones, 0, and values just past Long.MAX_VALUE) with at most one character
     * replaced, inserted or removed.
     */
    private static Gen<String> mutatedDecimals() {
        Gen<String> decimals = Gens.oneOf(
                Gens.longs(1, Long.MAX_VALUE).map(value -> Long.toString(value)),
                Gens.longs(1, 999).map(value -> Long.toString(value)),
                Gens.longs(0, 999)
                        .map(extra -> BigInteger.valueOf(Long.MAX_VALUE)
                                .add(BigInteger.valueOf(extra))
                                .toString()));
        String noise = "0123456789+- x";
        return random -> {
            StringBuilder text = new StringBuilder(decimals.next(random));
            int at = random.nextInt(text.length() + 1);
            char c = noise.charAt(random.nextInt(noise.length()));
            switch (random.nextInt(4)) {
                case 0 -> {}
                case 1 -> text.insert(at, c);
                case 2 -> text.setCharAt(Math.min(at, text.length() - 1), c);
                default -> text.deleteCharAt(Math.min(at, text.length() - 1));
            }
            return text.toString();
        };
    }

    /** The rule written another way: the text is how Java prints some long from 1 to Long.MAX_VALUE. */
    private static boolean isCanonicalPositiveLong(String text) {
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException notALong) {
            return false;
        }
        return value > 0 && Long.toString(value).equals(text);
    }
}
