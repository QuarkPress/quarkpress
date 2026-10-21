package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermHelpersPublicTest {

    @Test
    void aLoneLessThanIsEscapedAndATagIsKept() {
        assertThat(Kses.lessThan("a < b")).isEqualTo("a &lt; b");
        assertThat(Kses.lessThan("<b>x</b>")).isEqualTo("<b>x</b>");
        assertThat(Kses.lessThan("x <y <b>z</b>")).isEqualTo("x &lt;y <b>z</b>");
        assertThat(Kses.lessThan("it's < \"q\"")).isEqualTo("it's &lt; &quot;q&quot;");
        assertThat(Kses.lessThan("plain")).isEqualTo("plain");
    }

    @Test
    void specialCharsEncodesAmpersandsAndAnglesButNotQuotes() {
        assertThat(Kses.specialChars("Rock & Roll")).isEqualTo("Rock &amp; Roll");
        assertThat(Kses.specialChars("x &amp; y")).isEqualTo("x &amp; y");
        assertThat(Kses.specialChars("a<b>c")).isEqualTo("a&lt;b&gt;c");
        assertThat(Kses.specialChars("Tom's \"best\"")).isEqualTo("Tom's \"best\"");
        assertThat(Kses.specialChars("&bogus; &copy; &#38;")).isEqualTo("&amp;bogus; &copy; &#038;");
        assertThat(Kses.specialChars("&apos;")).isEqualTo("&amp;apos;");
        assertThat(Kses.specialChars("")).isEmpty();
    }
}
