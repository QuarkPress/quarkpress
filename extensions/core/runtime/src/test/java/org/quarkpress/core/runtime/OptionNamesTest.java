package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class OptionNamesTest {

    // Built from code points: the formatter would turn escapes of printable characters into literals.
    private static final String GRINNING_FACE = new String(Character.toChars(0x1F600));
    private static final String NO_BREAK_SPACE = String.valueOf((char) 0xA0);
    private static final String LONE_HIGH = String.valueOf((char) 0xD800);
    private static final String LONE_LOW = String.valueOf((char) 0xDC00);

    @Test
    void trimsWhatPhpTrimRemoves() {
        assertThat(OptionNames.normalize(" blogname\n")).isEqualTo("blogname");
        assertThat(OptionNames.normalize("\u0000\t\r\u000Bname \u0000")).isEqualTo("name");
    }

    @Test
    void zeroIsOnlyRejectedAlone() {
        assertThat(OptionNames.normalize("00")).isEqualTo("00");
        assertThat(OptionNames.normalize("0a")).isEqualTo("0a");
        assertThatThrownBy(() -> OptionNames.normalize("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"0\"");
    }

    @Test
    void keepsOtherWhitespace() {
        assertThat(OptionNames.normalize(NO_BREAK_SPACE + "name")).isEqualTo(NO_BREAK_SPACE + "name");
    }

    static Stream<String> rejected() {
        return Stream.of("", " ", "\n\t", "a\u0000b", LONE_HIGH + "x", "x" + LONE_LOW, "0", " 0\n");
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectsEmptyNulInsideAndLoneSurrogates(String name) {
        assertThatThrownBy(() -> OptionNames.normalize(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitCountsCharactersNotUtf16Units() {
        assertThat(OptionNames.normalize(GRINNING_FACE.repeat(191))).hasSize(382);
        assertThatThrownBy(() -> OptionNames.normalize(GRINNING_FACE.repeat(192)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("191");
        assertThat(OptionNames.normalize("a".repeat(191))).hasSize(191);
        assertThatThrownBy(() -> OptionNames.normalize("a".repeat(192))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizedNamesAreStable() {
        Properties.check("OptionNamesTest.idempotent", 5000, Gens.strings(Gens.unicodeChars(), 0, 200), name -> {
            String normalized;
            try {
                normalized = OptionNames.normalize(name);
            } catch (IllegalArgumentException rejected) {
                return;
            }
            assertThat(OptionNames.normalize(normalized)).isEqualTo(normalized);
            assertThat(normalized.codePointCount(0, normalized.length())).isBetween(1, 191);
        });
    }

    /** A code point that is never trimmed and never rejected: printable ASCII, other BMP or a supplementary one. */
    private static final Gen<Integer> CORE_CODE_POINTS = Gens.frequency(
            Gens.weighted(3, Gens.ints('!', '~')),
            Gens.weighted(2, Gens.ints(0xA0, 0xFFFF).filter(c -> !Character.isSurrogate((char) c.intValue()))),
            Gens.weighted(2, Gens.ints(0x10000, 0x10FFFF)));

    private static final Gen<String> TRIMMED = Gens.strings(" \t\n\r\u0000\u000B", 0, 4);

    private static Gen<String> cores(int minCodePoints, int maxCodePoints) {
        return Gens.lists(CORE_CODE_POINTS, minCodePoints, maxCodePoints).map(points -> {
            StringBuilder core = new StringBuilder();
            points.forEach(core::appendCodePoint);
            return core.toString();
        });
    }

    @Test
    void validCoresSurviveTrimmingUnchanged() {
        AtomicInteger accepted = new AtomicInteger();
        Gen<String> core = cores(1, 191);
        Gen<List<String>> parts = random -> List.of(TRIMMED.next(random), core.next(random), TRIMMED.next(random));
        Properties.check("OptionNamesTest.cores", 3000, parts, part -> {
            String middle = part.get(1);
            if (middle.equals("0")) {
                return;
            }
            assertThat(OptionNames.normalize(part.get(0) + middle + part.get(2)))
                    .isEqualTo(middle);
            accepted.incrementAndGet();
        });
        assertThat(accepted.get()).isGreaterThan(2900);
    }

    @Test
    void namesOverTheLimitAreRejected() {
        Properties.check(
                "OptionNamesTest.tooLong",
                500,
                cores(192, 400),
                core -> assertThatThrownBy(() -> OptionNames.normalize(core))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("191"));
    }
}
