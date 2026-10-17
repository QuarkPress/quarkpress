package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CharsTest {

    @Test
    void wordCharsAreAsciiOnly() {
        assertThat(Chars.isWord('a')).isTrue();
        assertThat(Chars.isWord('Z')).isTrue();
        assertThat(Chars.isWord('7')).isTrue();
        assertThat(Chars.isWord('_')).isTrue();
        assertThat(Chars.isWord('-')).isFalse();
        assertThat(Chars.isWord('\u00FC')).isFalse();
        assertThat(Chars.isWord('\uD83D')).isFalse();
    }

    @Test
    void spacesAreTheSixAsciiSpaces() {
        for (char c : new char[] {' ', '\t', '\n', '\u000B', '\f', '\r'}) {
            assertThat(Chars.isSpace(c)).as("U+%04X", (int) c).isTrue();
        }
        assertThat(Chars.isSpace('\u00A0')).isFalse();
        assertThat(Chars.isSpace('\u2003')).isFalse();
        assertThat(Chars.isSpace('\u0000')).isFalse();
    }

    @Test
    void nameTerminatorsAreControlsSpaceAndSevenPunctuationChars() {
        for (char c : "<>&/[]= \t\u0001\u0000".toCharArray()) {
            assertThat(Chars.endsName(c)).as("U+%04X", (int) c).isTrue();
        }
        for (char c : ".:!\"'|{}-_\u007F\u00FC".toCharArray()) {
            assertThat(Chars.endsName(c)).as("U+%04X", (int) c).isFalse();
        }
    }

    @Test
    void lowercasesAsciiOnly() {
        assertThat(Chars.asciiLower("\u00C4bC-D")).isEqualTo("\u00C4bc-d");
        String lower = "abc";
        assertThat(Chars.asciiLower(lower)).isSameAs(lower);
    }

    @Test
    void findsUnpairedSurrogates() {
        assertThat(Chars.hasUnpairedSurrogate("a\uD83D\uDE00b")).isFalse();
        assertThat(Chars.hasUnpairedSurrogate("a\uD83Db")).isTrue();
        assertThat(Chars.hasUnpairedSurrogate("a\uDE00")).isTrue();
        assertThat(Chars.hasUnpairedSurrogate("\uD83D")).isTrue();
    }

    @Test
    void phpTrimSetHasNoFormFeed() {
        assertThat(Chars.isPhpBlank(" \t\n\r\u0000\u000B")).isTrue();
        assertThat(Chars.isPhpBlank("\f")).isFalse();
        assertThat(Chars.phpTrim("\t x \u0000")).isEqualTo("x");
        assertThat(Chars.phpTrim("\fx")).isEqualTo("\fx");
    }

    @Test
    void removesTheControlsKsesRemoves() {
        assertThat(Chars.removeControls("a\u0000b\u0008c\td\ne\u000Bf\u000Cg\rh\u001Fi\u007Fj"))
                .isEqualTo("abc\td\nefg\rhi\u007Fj");
    }

    @Test
    void comparesPrefixesIgnoringAsciiCaseOnly() {
        assertThat(Chars.startsWithIgnoreAsciiCase("x&CoLoN;", 1, "&colon;")).isTrue();
        assertThat(Chars.startsWithIgnoreAsciiCase("&colo", 0, "&colon;")).isFalse();
        assertThat(Chars.startsWithIgnoreAsciiCase("\u212Aey", 0, "key")).isFalse();
    }
}
