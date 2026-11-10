package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PHP's {@code trim} and {@code ltrim} with their default characters, as {@code /usr/bin/php} answers them. */
class PhpValuesTest {

    @Test
    void trimRemovesPhpsDefaultCharactersAtBothEnds() {
        // a form feed and a no-break space are not among them
        assertThat(PhpValues.trim(" \t\n\r\u0000\u000B\fa b\u00A0\u000B ")).isEqualTo("\fa b\u00A0");
        assertThat(PhpValues.trim(" \t ")).isEmpty();
        assertThat(PhpValues.trim("")).isEmpty();
    }

    @Test
    void trimOfARangeTrimsThatRangeAlone() {
        assertThat(PhpValues.trim("x;  name = \"a\" ;y", 2, 9)).isEqualTo("name");
        assertThat(PhpValues.trim("  ", 0, 2)).isEmpty();
    }

    @Test
    void ltrimRemovesThemAtTheStartOnly() {
        assertThat(PhpValues.ltrim("\u0000\u000B x \t")).isEqualTo("x \t");
        assertThat(PhpValues.ltrim("\n")).isEmpty();
    }
}
