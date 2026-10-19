package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoveAccentsTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void asciiIsUnchanged() {
        assertThat(RemoveAccents.enUs("Plain ASCII 123 <&>")).isEqualTo("Plain ASCII 123 <&>");
    }

    @Test
    void latinLettersLoseTheirMarks() {
        assertThat(RemoveAccents.enUs(cp(0xC9) + "milie")).isEqualTo("Emilie");
        assertThat(RemoveAccents.enUs("Zo" + cp(0xEB) + " " + cp(0xDC) + "nal")).isEqualTo("Zoe Unal");
        assertThat(RemoveAccents.enUs(cp(0x1D6))).isEqualTo("u");
        assertThat(RemoveAccents.enUs(cp(0x218, 0x21B))).isEqualTo("St");
        assertThat(RemoveAccents.enUs(cp(0x1EA1, 0x1EF9))).isEqualTo("ay");
        assertThat(RemoveAccents.enUs(cp(0x1A0, 0x1B0))).isEqualTo("Ou");
    }

    @Test
    void lettersThatDoNotDecomposeUseTheExplicitList() {
        assertThat(RemoveAccents.enUs("Stra" + cp(0xDF) + "e")).isEqualTo("Strase");
        assertThat(RemoveAccents.enUs(cp(0xD8) + "yvind")).isEqualTo("Oyvind");
        assertThat(RemoveAccents.enUs(cp(0xC6, 0x152, 0xDE, 0x132))).isEqualTo("AEOETHIJ");
        assertThat(RemoveAccents.enUs(cp(0x110, 0x142, 0x131, 0x138, 0x149, 0x17F)))
                .isEqualTo("Dlikns");
        assertThat(RemoveAccents.enUs(cp(0x20AC) + "5" + cp(0xA3))).isEqualTo("E5");
        assertThat(RemoveAccents.enUs(cp(0x18F, 0x1DD))).isEqualTo("Ee");
        assertThat(RemoveAccents.enUs("m" + cp(0x251) + "ma")).isEqualTo("mama");
    }

    @Test
    void decomposedInputIsComposedFirst() {
        assertThat(RemoveAccents.enUs("e" + cp(0x301) + "te")).isEqualTo("ete");
    }

    @Test
    void lettersOutsideTheTableAreKept() {
        String greek = cp(0x395, 0x3BB, 0x3AD, 0x3BD, 0x3B7);
        assertThat(RemoveAccents.enUs(greek)).isEqualTo(greek);
        assertThat(RemoveAccents.enUs(cp(0x1FA))).isEqualTo(cp(0x1FA));
        assertThat(RemoveAccents.enUs(cp(0x1E00))).isEqualTo(cp(0x1E00));
        assertThat(RemoveAccents.enUs(cp(0xD7, 0xF7))).isEqualTo(cp(0xD7, 0xF7));
        assertThat(RemoveAccents.enUs(cp(0x1F600))).isEqualTo(cp(0x1F600));
    }
}
