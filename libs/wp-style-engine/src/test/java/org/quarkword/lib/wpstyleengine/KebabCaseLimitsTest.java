package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Kebab case beyond the JDK's Unicode version, and on text that is not valid UTF-8 (T1-R). */
class KebabCaseLimitsTest {

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    // WordPress's answers (php 8, PCRE2 10.49, Unicode 17.0); also the parity cases t1-kebab-u16-* and t1-kebab-u17-*
    @Test
    void unicode16And17DigitsAndLetters() {
        Map<String, String> expected = new LinkedHashMap<>();
        // Unicode 16 digits: Garay, Ol Onal, Kirat Rai
        expected.put("a" + cp(0x10D40) + "b", "a-" + cp(0x10D40) + "-b");
        expected.put(cp(0x10D40) + cp(0x10D49) + "px", cp(0x10D40) + cp(0x10D49) + "-px");
        expected.put("x" + cp(0x1E5F1) + "y", "x-" + cp(0x1E5F1) + "-y");
        expected.put("x" + cp(0x16D70) + "Y", "x-" + cp(0x16D70) + "-y");
        // Unicode 17 digit
        expected.put("a" + cp(0x11DE0) + "b", "a-" + cp(0x11DE0) + "-b");
        // an ordinal followed by a Unicode 16 or 17 word character is no ordinal
        expected.put("1st" + cp(0x10D50), "1-st" + cp(0x10D50));
        expected.put("1st" + cp(0x11B60), "1-st" + cp(0x11B60));
        expected.put("1st" + cp(0x10940), "1-st" + cp(0x10940));
        expected.put("4th" + cp(0x10D69), "4-th" + cp(0x10D69));
        expected.put("2nd" + cp(0x11DE0), "2-nd-" + cp(0x11DE0));
        expected.put("3rd" + cp(0x16130), "3-rd-" + cp(0x16130));
        // a spacing mark is no word character
        expected.put("1stः", "1st-ः");
        expected.forEach((in, out) -> assertThat(KebabCase.of(in)).as(in).isEqualTo(out));
    }

    // WordPress's /u pattern matches nothing on invalid UTF-8 (php: 61 ED A0 80 62 and 61 FF 62 give "")
    @Test
    void unpairedSurrogateGivesEmpty() {
        assertThat(KebabCase.of("a\uD800b")).isEmpty();
        assertThat(KebabCase.of("\uDC00X")).isEmpty();
        assertThat(KebabCase.of("aB\uD83D")).isEmpty();
        assertThat(KebabCase.of("a😀b")).isEqualTo("a😀b");
    }

    @Test
    void tablesAreSortedDisjointRanges() {
        for (int[] table : new int[][] {UnicodeTables.DIGIT, UnicodeTables.WORD}) {
            assertThat(table.length % 2).isZero();
            for (int i = 0; i < table.length; i += 2) {
                assertThat(table[i]).isLessThanOrEqualTo(table[i + 1]);
                if (i > 0) {
                    assertThat(table[i]).isGreaterThan(table[i - 1] + 1);
                }
            }
        }
        assertThat(UnicodeTables.contains(UnicodeTables.DIGIT, '0')).isTrue();
        assertThat(UnicodeTables.contains(UnicodeTables.DIGIT, '9')).isTrue();
        assertThat(UnicodeTables.contains(UnicodeTables.DIGIT, '/')).isFalse();
        assertThat(UnicodeTables.contains(UnicodeTables.DIGIT, 0x11DE9)).isTrue();
        assertThat(UnicodeTables.contains(UnicodeTables.DIGIT, 0x10FFFF)).isFalse();
        assertThat(UnicodeTables.contains(UnicodeTables.WORD, '_')).isTrue();
        assertThat(UnicodeTables.contains(UnicodeTables.WORD, 0x0300)).isTrue();
        assertThat(UnicodeTables.contains(UnicodeTables.WORD, 0x0903)).isFalse();
        assertThat(UnicodeTables.contains(UnicodeTables.WORD, 0x11B60)).isTrue();
    }

    @Test
    void phpJsonMapKeepsNullValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("0", null);
        assertThat(PhpJson.map(m).toString()).isEqualTo("[null]");
    }
}
