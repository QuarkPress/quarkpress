package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextUtilsTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("text-utils.json");
    }

    @Test
    void decodeNumericBytesTakesTheNumberModulo256() {
        // R15.1 (verified): the reference lists the result as the bytes A A A A &#x; &#65 NUL NUL E8 : : NUL
        String in = "&#65;&#x41;&#X41;&#0065;&#x;&#65 &#256;&#x100;&#1000;&#x3A;&#58;&#0;";
        assertThat(TextUtils.decodeNumericBytes(in)).isEqualTo("AAAA&#x;&#65 \000\000\350::\000");
        assertThat(TextUtils.decodeNumericBytes("&amp;&lt;&#38;")).isEqualTo("&amp;&lt;&");
    }

    @Test
    void decodeNumericBytesRunsTheHexPassOnTheDecimalResult() {
        // Two passes, as in WordPress: "&#38;" becomes "&", which then starts "&#x41;".
        assertThat(TextUtils.decodeNumericBytes("&#38;#x41;")).isEqualTo("A");
    }

    @Test
    void phpTrimStripsNulAndVerticalTabButNotFormFeed() {
        assertThat(TextUtils.phpTrim(" \t\n\r\000\013x\013 ")).isEqualTo("x");
        assertThat(TextUtils.phpTrim("\fx\f")).isEqualTo("\fx\f");
    }

    @Test
    void asciiLowerLeavesNonAsciiLettersAlone() {
        assertThat(TextUtils.asciiLower("DATA-É")).isEqualTo("data-É");
    }

    @Test
    void phpEmptyIsTrueForEmptyAndZero() {
        assertThat(TextUtils.phpEmpty("")).isTrue();
        assertThat(TextUtils.phpEmpty("0")).isTrue();
        assertThat(TextUtils.phpEmpty("00")).isFalse();
        assertThat(TextUtils.phpEmpty(" ")).isFalse();
    }

    @Test
    void loneSurrogatesBecomeReplacementCharacters() {
        String cow = new String(Character.toChars(0x1F42E));
        assertThat(TextUtils.wellFormed("a" + (char) 0xD800 + "b" + (char) 0xDC00 + cow))
                .isEqualTo("a" + TextUtils.REPLACEMENT_CHARACTER + "b" + TextUtils.REPLACEMENT_CHARACTER + cow);
        String ok = "fine " + cow;
        assertThat(TextUtils.wellFormed(ok)).isSameAs(ok);
    }

    @Test
    void utf8LengthCountsBytes() {
        assertThat(TextUtils.utf8Length("abc")).isEqualTo(3);
        assertThat(TextUtils.utf8Length("éé")).isEqualTo(4);
        assertThat(TextUtils.utf8Length(new String(Character.toChars(0x1F42E)))).isEqualTo(4);
        assertThat(TextUtils.utf8Length("€")).isEqualTo(3);
    }
}
