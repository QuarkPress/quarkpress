package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeysTest {

    // SE34, G126, G130
    @Test
    void wordPressExamples() {
        assertThat(Keys.sanitizeKey("Color")).isEqualTo("color");
        assertThat(Keys.sanitizeKey("min height")).isEqualTo("minheight");
        assertThat(Keys.sanitizeKey("position:fixed")).isEqualTo("positionfixed");
        assertThat(Keys.sanitizeKey("f\u00f6nt-size")).isEqualTo("fnt-size");
        assertThat(Keys.sanitizeKey("--My_Var")).isEqualTo("--my_var");
        assertThat(Keys.sanitizeKey("--x.y")).isEqualTo("--xy");
        assertThat(Keys.sanitizeKey("")).isEmpty();
    }

    // Review Focus 1: the rf1-key-* parity cases (WordPress's answers)
    @Test
    void nonAscii() {
        assertThat(Keys.sanitizeKey("\u00c4B-c")).isEqualTo(RF1_KEY_1);
        assertThat(Keys.sanitizeKey("--\u216b")).isEqualTo(RF1_KEY_2);
        assertThat(Keys.sanitizeKey("\u212aelvin")).isEqualTo(RF1_KEY_3);
        assertThat(Keys.sanitizeKey("a\u0000b")).isEqualTo(RF1_KEY_4);
        assertThat(Keys.sanitizeKey("\ud83d\ude00Z")).isEqualTo("z");
    }

    private static final String RF1_KEY_1 = "b-c";
    private static final String RF1_KEY_2 = "--";
    private static final String RF1_KEY_3 = "elvin";
    private static final String RF1_KEY_4 = "ab";
}
