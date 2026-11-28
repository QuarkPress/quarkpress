package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KebabCaseTest {

    // SE16, G2, G16, G17, G196
    @Test
    void wordPressExamples() {
        assertThat(KebabCase.of("heavenlyBlue")).isEqualTo("heavenly-blue");
        assertThat(KebabCase.of("accent2Dark")).isEqualTo("accent-2-dark");
        assertThat(KebabCase.of("my_colour two")).isEqualTo("my-colour-two");
        assertThat(KebabCase.of("a.b")).isEqualTo("a-b");
        assertThat(KebabCase.of("10px")).isEqualTo("10-px");
        assertThat(KebabCase.of("x-large")).isEqualTo("x-large");
        assertThat(KebabCase.of("0")).isEqualTo("0");
        assertThat(KebabCase.of("\u00dcn\u00efcode")).isEqualTo("\u00dcn\u00efcode");
    }

    // SE72, SE73: acronyms, ordinals and digits (G212, G219, G229-G235, G238)
    @Test
    void acronymsOrdinalsAndDigits() {
        assertThat(KebabCase.of("ABCDef")).isEqualTo("abc-def");
        assertThat(KebabCase.of("XMLHttpRequest")).isEqualTo("xml-http-request");
        assertThat(KebabCase.of("ABc")).isEqualTo("a-bc");
        assertThat(KebabCase.of("21stCentury")).isEqualTo("21st-century");
        assertThat(KebabCase.of("3RD")).isEqualTo("3rd");
        assertThat(KebabCase.of("11th")).isEqualTo("11-th");
        assertThat(KebabCase.of("a1stB")).isEqualTo("a-1st-b");
        assertThat(KebabCase.of("v2Beta3")).isEqualTo("v-2-beta-3");
        assertThat(KebabCase.of("")).isEmpty();
        assertThat(KebabCase.of("---")).isEmpty();
    }

    // Review Focus 1: the rf1-kebab-* parity cases (WordPress's answers)
    @Test
    void unicodeAndApostrophes() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("XMLHttpRequest", "xml-http-request");
        expected.put("\u00c4pfelBlau", "\u00c4pfel-blau");
        expected.put("foo\u00c9tat", "foo-\u00c9tat");
        expected.put("\u0130stanbul", "\u0130stanbul");
        expected.put("stra\u00dfe", "stra\u00dfe");
        expected.put("don't", "dont");
        expected.put("don\u2019t", "don-t");
        expected.put("a\ud83d\ude00b", "a\uD83D\uDE00b");
        expected.put("\u0661\u0662\u0663abc", "\u0661\u0662\u0663-abc");
        expected.put("\ufb01x", "\ufb01x");
        expected.put("camelCase123Ab", "camel-case-123-ab");
        expected.put("__a--b__", "a-b");
        expected.put("ABC", "abc");
        expected.put("aB C", "a-b-c");
        expected.forEach((in, out) -> assertThat(KebabCase.of(in)).as(in).isEqualTo(out));
    }
}
