package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CharacterReferencesTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("decode-attribute.json");
    }

    @Test
    void tableHasEveryHtml5Name() {
        assertThat(CharacterReferences.named("amp;")).isEqualTo("&");
        assertThat(CharacterReferences.named("amp")).isEqualTo("&");
        assertThat(CharacterReferences.named("notin")).isNull();
        assertThat(CharacterReferences.named("CounterClockwiseContourIntegral;"))
                .hasSize(1)
                .isEqualTo(new String(Character.toChars(0x2233)));
        assertThat(CharacterReferences.named("NotNestedGreaterGreater;"))
                .isEqualTo(new String(new char[] {(char) 0x2AA2, (char) 0x0338}));
    }

    @Test
    void numericReferencesFollowHtml5() {
        assertThat(CharacterReferences.codePointFor(0)).isEqualTo(0xFFFD);
        assertThat(CharacterReferences.codePointFor(0xD800)).isEqualTo(0xFFFD);
        assertThat(CharacterReferences.codePointFor(0x110000)).isEqualTo(0xFFFD);
        assertThat(CharacterReferences.codePointFor(0x80)).isEqualTo(0x20AC);
        assertThat(CharacterReferences.codePointFor(0x81)).isEqualTo(0x81);
        assertThat(CharacterReferences.codePointFor(0x9F)).isEqualTo(0x178);
        assertThat(CharacterReferences.codePointFor(0x41)).isEqualTo(0x41);
    }

    @Test
    void textWithoutAmpersandIsReturnedAsIs() {
        String s = "no references here";
        assertThat(CharacterReferences.decodeAttribute(s)).isSameAs(s);
    }

    @Test
    void longRunsOfLettersAreBounded() {
        String s = "&" + "a".repeat(100_000) + ";";
        assertThat(CharacterReferences.decodeAttribute(s)).isEqualTo(s);
    }
}
