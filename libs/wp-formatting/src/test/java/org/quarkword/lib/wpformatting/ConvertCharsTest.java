package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ConvertCharsTest {

    @Test
    void nonAsciiAfterAmpersandAlwaysConverts() { // H3, A-G140
        assertThat(Formatting.convertChars("&éamp;")).isEqualTo("&#038;éamp;");
        // WordPress's answer for case t4-emoji: the & before a surrogate pair is escaped, the pair stays whole
        assertThat(Formatting.convertChars("&😀;")).isEqualTo("&#038;😀;");
    }

    @Test
    void onlyAnEscapedAmpersandConsumesTheCharAfterIt() { // A-G96, WordPress's answers from the fuzz
        // a kept & matches nothing, so the next & is checked on its own
        assertThat(Formatting.convertChars("&&amp;")).isEqualTo("&&amp;");
        assertThat(Formatting.convertChars("&&a;")).isEqualTo("&&#038;a;");
        // an escaped & takes the char after it along, even when that char is an &
        assertThat(Formatting.convertChars("&&x")).isEqualTo("&#038;&x");
        assertThat(Formatting.convertChars("&&")).isEqualTo("&#038;&");
        assertThat(Formatting.convertChars("&&&a;")).isEqualTo("&#038;&&#038;a;");
    }

    @Test
    void nameTailIsOneToEightChars() { // FA47, A-G95
        assertThat(Formatting.convertChars("&a;")).isEqualTo("&#038;a;");
        assertThat(Formatting.convertChars("&ab;")).isEqualTo("&ab;");
        assertThat(Formatting.convertChars("&abcdefghi;")).isEqualTo("&abcdefghi;");
        assertThat(Formatting.convertChars("&abcdefghij;")).isEqualTo("&#038;abcdefghij;");
        assertThat(Formatting.convertChars("&x1234;&x5;&X0;")).isEqualTo("&x1234;&#038;x5;&#038;X0;");
    }

    @Test
    void numericReferencesAndAFinalAmpersandAreKept() { // A-G93, A-G98
        assertThat(Formatting.convertChars("&#xZZ &#")).isEqualTo("&#xZZ &#");
        assertThat(Formatting.convertChars("tail&")).isEqualTo("tail&");
    }

    @Test
    void textWithoutAmpersandIsReturnedAsIs() { // FA46
        String text = "no ampersand here";
        assertThat(Formatting.convertChars(text)).isSameAs(text);
        String kept = "&amp; &copy;";
        assertThat(Formatting.convertChars(kept)).isSameAs(kept);
        assertThatNullPointerException().isThrownBy(() -> Formatting.convertChars(null));
    }
}
