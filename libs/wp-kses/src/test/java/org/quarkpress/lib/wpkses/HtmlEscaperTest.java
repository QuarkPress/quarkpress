package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlEscaperTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("escape.json");
    }

    @Test
    void textWithoutSpecialCharactersIsReturnedAsIs() {
        String s = "plain text";
        assertThat(HtmlEscaper.escape(s)).isSameAs(s);
    }
}
