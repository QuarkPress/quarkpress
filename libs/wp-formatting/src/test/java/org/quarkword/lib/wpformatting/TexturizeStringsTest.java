package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TexturizeStringsTest {

    @Test
    void englishHasWordPresssEntities() { // FT4, FT6
        TexturizeStrings en = TexturizeStrings.english();
        assertThat(en.openingDoubleQuote()).isEqualTo("&#8220;");
        assertThat(en.closingDoubleQuote()).isEqualTo("&#8221;");
        assertThat(en.apostrophe()).isEqualTo("&#8217;");
        assertThat(en.prime()).isEqualTo("&#8242;");
        assertThat(en.doublePrime()).isEqualTo("&#8243;");
        assertThat(en.openingSingleQuote()).isEqualTo("&#8216;");
        assertThat(en.closingSingleQuote()).isEqualTo("&#8217;");
        assertThat(en.enDash()).isEqualTo("&#8211;");
        assertThat(en.emDash()).isEqualTo("&#8212;");
        assertThat(en.cockney())
                .hasSize(11)
                .containsKeys("'tain't")
                .containsEntry("'tain't", "&#8217;tain&#8217;t")
                .containsEntry("'em", "&#8217;em");
        assertThat(en.cockney().keySet())
                .containsExactly(
                        "'tain't", "'twere", "'twas", "'tis", "'twill", "'til", "'bout", "'nuff", "'round", "'cause",
                        "'em");
    }

    @Test
    void rejectsTheAsciiCharacterItself() { // DC3
        TexturizeStrings en = TexturizeStrings.english();
        assertThatThrownBy(() -> new TexturizeStrings(
                        en.openingDoubleQuote(),
                        en.closingDoubleQuote(),
                        "'",
                        en.prime(),
                        en.doublePrime(),
                        en.openingSingleQuote(),
                        en.closingSingleQuote(),
                        en.enDash(),
                        en.emDash(),
                        en.cockney()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TexturizeStrings(
                        "\"",
                        en.closingDoubleQuote(),
                        en.apostrophe(),
                        en.prime(),
                        en.doublePrime(),
                        en.openingSingleQuote(),
                        en.closingSingleQuote(),
                        en.enDash(),
                        en.emDash(),
                        en.cockney()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TexturizeStrings(
                        en.openingDoubleQuote(),
                        en.closingDoubleQuote(),
                        en.apostrophe(),
                        en.prime(),
                        en.doublePrime(),
                        en.openingSingleQuote(),
                        en.closingSingleQuote(),
                        "-",
                        en.emDash(),
                        en.cockney()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException()
                .isThrownBy(() -> new TexturizeStrings(
                        null,
                        en.closingDoubleQuote(),
                        en.apostrophe(),
                        en.prime(),
                        en.doublePrime(),
                        en.openingSingleQuote(),
                        en.closingSingleQuote(),
                        en.enDash(),
                        en.emDash(),
                        en.cockney()));
    }

    @Test
    void cockneyIsCopiedAndReplacedCompletely() { // FT6
        Map<String, String> mine = new LinkedHashMap<>();
        mine.put("'z", "Z");
        mine.put("'a", "A");
        TexturizeStrings custom = TexturizeStrings.english().withCockney(mine);
        mine.put("'b", "B");
        assertThat(custom.cockney().keySet()).containsExactly("'z", "'a");
        assertThatThrownBy(() -> custom.cockney().put("'c", "C")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(custom.apostrophe()).isEqualTo("&#8217;");
    }
}
