package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharsTest {

    @Test
    void theThreeSetsDiffer() { // Review Focus 1; H1, H2
        assertThat(Formatting.phpTrim("\f x \u000b\u0000")).isEqualTo("\f x");
        assertThat(Formatting.phpTrim(" x ")).isEqualTo("x");

        List<Integer> pcre = new ArrayList<>();
        for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
            if (Chars.isPcreSpace((char) c)) {
                pcre.add(c);
            }
        }
        assertThat(pcre).containsExactly((int) '\t', (int) '\n', 0x0B, (int) '\f', (int) '\r', (int) ' ');

        List<Integer> trim = new ArrayList<>();
        for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
            if (Chars.isPhpTrim((char) c)) {
                trim.add(c);
            }
        }
        assertThat(trim).containsExactly(0, (int) '\t', (int) '\n', 0x0B, (int) '\r', (int) ' ');

        assertThat(Spaces.lengthAt("&nbsp;x", 0)).isEqualTo(6);
        assertThat(Spaces.lengthAt(" ", 0)).isEqualTo(1);
        assertThat(Spaces.lengthAt("\u000b", 0)).isZero();
        assertThat(Spaces.lengthEndingAt("a&nbsp;", 7)).isEqualTo(6);
    }

    @Test
    void spMembers() {
        for (String member : List.of("\r", "\n", "\t", " ", "\u00a0")) {
            assertThat(Spaces.lengthAt("x" + member, 1))
                    .as("%s", (int) member.charAt(0))
                    .isEqualTo(1);
            assertThat(Spaces.lengthEndingAt(member + "x", 1)).isEqualTo(1);
        }
        for (String other :
                List.of("\f", "\u000b", "\u0000", "\u0085", "\u2028", "\u2029", "\u3000", "&NBSP;", "&nbsp")) {
            assertThat(Spaces.lengthAt(other, 0)).as("%s", other).isZero();
            assertThat(Spaces.lengthEndingAt(other, other.length()))
                    .as("%s", other)
                    .isZero();
        }
        assertThat(Spaces.lengthAt("", 0)).isZero();
        assertThat(Spaces.lengthAt("x", 1)).isZero();
        assertThat(Spaces.lengthEndingAt("", 0)).isZero();
        assertThat(Spaces.lengthEndingAt("nbsp;", 5)).isZero();
    }

    @Test
    void asciiClasses() {
        assertThat(Chars.isAsciiDigit('7')).isTrue();
        assertThat(Chars.isAsciiDigit('٣')).isFalse();
        assertThat(Chars.isAsciiLetter('Z')).isTrue();
        assertThat(Chars.isAsciiLetter('é')).isFalse();
        assertThat(Chars.isAsciiLetter('K')).isFalse();
        assertThat(Chars.isAsciiWord('_')).isTrue();
        assertThat(Chars.isAsciiWord('-')).isFalse();
    }
}
