package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttributeJsonTest {

    @Test
    void parsesObjectsPreservingKeyOrder() {
        ObjectNode n = AttributeJson.parse("{\"b\": 1, \"a\": [true, null, \"x\"], \"c\": {\"d\": 1.5}}");
        assertThat(n.fieldNames()).toIterable().containsExactly("b", "a", "c");
        assertThat(n.get("a").get(2).textValue()).isEqualTo("x");
        assertThat(n.get("c").get("d").doubleValue()).isEqualTo(1.5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1} x", "{\"a\":}", "{'a':1}", "{\"a\":1,}", "{\"a\":1}{}"})
    void invalidJsonBecomesEmptyObject(String raw) {
        assertThat(AttributeJson.parse(raw).isEmpty()).isTrue();
    }

    @Test
    void writeIsCompactAndKeepsInsertionOrder() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("z", 1).put("a", "x").putArray("l").add(1).add(2);
        assertThat(AttributeJson.write(n)).isEqualTo("{\"z\":1,\"a\":\"x\",\"l\":[1,2]}");
    }

    @Test
    void writeEscapesCommentBreakingSequences() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("s", "a--b <i>&amp;</i> \"q\" ---");
        String out = AttributeJson.write(n);
        assertThat(out)
                .isEqualTo(
                        "{\"s\":\"a\\u002d\\u002db \\u003ci\\u003e\\u0026amp;\\u003c/i\\u003e \\u0022q\\u0022 \\u002d\\u002d-\"}");
        assertThat(out)
                .doesNotContain("--")
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain("&");
        assertThat(AttributeJson.parse(out)).isEqualTo(n);
    }

    @Test
    void writeDoesNotMistakeEscapedBackslashForQuote() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("p", "C:\\"); // value ends with a backslash: Jackson writes "C:\\" and the closing quote is structural
        String out = AttributeJson.write(n);
        assertThat(out).isEqualTo("{\"p\":\"C:\\u005c\"}");
        assertThat(AttributeJson.parse(out)).isEqualTo(n);
    }

    @Test
    void writeOfEmptyObjectIsBraces() {
        assertThat(AttributeJson.write(AttributeJson.emptyObject())).isEqualTo("{}");
    }

    @Test
    void nonAsciiIsWrittenAsIs() {
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("t", "héllo – ✓");
        assertThat(AttributeJson.write(n)).isEqualTo("{\"t\":\"héllo – ✓\"}");
    }

    @Test
    void nonFiniteNumbersAreWrittenAsNull() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("nan", Double.NaN).put("pos", Double.POSITIVE_INFINITY).put("neg", Double.NEGATIVE_INFINITY);
        n.put("fnan", Float.NaN);
        n.putArray("a").add(Double.NaN).add(1).add(Double.POSITIVE_INFINITY).add(Double.NEGATIVE_INFINITY);
        n.putObject("o").put("x", Double.NaN);
        assertThat(AttributeJson.write(n))
                .isEqualTo("{\"nan\":null,\"pos\":null,\"neg\":null,\"fnan\":null,"
                        + "\"a\":[null,1,null,null],\"o\":{\"x\":null}}");
        assertThat(n.get("nan").isNumber()).isTrue(); // the caller's tree is not modified
    }

    @Test
    void overflowingNumberParsesAndWritesAsNull() {
        ObjectNode n = AttributeJson.parse("{\"i\":1e400}");
        assertThat(AttributeJson.write(n)).isEqualTo("{\"i\":null}");
    }

    @Test
    void unpairedSurrogateEscapesAreRejectedLikePhp() { // D8
        assertThat(AttributeJson.parseOrNull("{\"a\":\"\\ud800\"}")).isNull();
        assertThat(AttributeJson.parseOrNull("{\"a\":\"\\udc00\"}")).isNull();
        assertThat(AttributeJson.parseOrNull("{\"a\":\"\\ud800x\"}")).isNull();
        assertThat(AttributeJson.parseOrNull("{\"\\ud800\":1}")).isNull();
        assertThat(AttributeJson.parseOrNull("{\"a\":\"\\ud83d\\ud83d\\ude00\"}"))
                .isNull();
        assertThat(AttributeJson.parseOrNull("{\"a\":\"\\ud83d\\ude00\"}")
                        .get("a")
                        .textValue())
                .isEqualTo(new String(Character.toChars(0x1F600)));
    }

    @Test
    void nestingFollowsPhpsDepthLimit() { // D8: PHP decodes 511 nested containers and rejects 512
        assertThat(AttributeJson.parseOrNull(nested(511))).isNotNull();
        assertThat(AttributeJson.parseOrNull(nested(512))).isNull();
        assertThat(AttributeJson.parseOrNull(nested(600))).isNull();
    }

    /** An object holding {@code containers - 1} nested arrays. */
    private static String nested(int containers) {
        return "{\"x\":" + "[".repeat(containers - 1) + "]".repeat(containers - 1) + "}";
    }

    @Test
    void stringsLongerThanJacksonsDefaultLimitAreAccepted() { // D9
        int length = StreamReadConstraints.DEFAULT_MAX_STRING_LEN + 1;
        ObjectNode parsed = AttributeJson.parseOrNull("{\"s\":\"" + "x".repeat(length) + "\"}");
        assertThat(parsed).isNotNull();
        assertThat(parsed.get("s").textValue()).hasSize(length);
    }

    @Test
    void integersLongerThanOneThousandDigitsStayRejected() { // D9, a documented difference (PHP reads a float)
        assertThat(AttributeJson.parseOrNull("{\"n\":1" + "0".repeat(1000) + "}"))
                .isNull();
    }

    @Test
    void numbersWithAnOverlongPartAreRejected() { // D9: any number part beyond 1000 characters, floats included
        assertThat(AttributeJson.parseOrNull("{\"n\":0." + "0".repeat(999) + "1}"))
                .isNotNull();
        assertThat(AttributeJson.parseOrNull("{\"n\":0." + "0".repeat(1000) + "1}"))
                .isNull();
    }

    @Test
    void loneSurrogateFromApiIsWrittenAndRejectedOnReparse() { // Review Focus 4
        ObjectNode attrs = JsonNodeFactory.instance.objectNode().put("s", "x" + (char) 0xD800);
        String doc = WpBlocks.serializeCanonical(List.of(Block.of("a", attrs)));
        assertThat(doc).isEqualTo("<!-- wp:a {\"s\":\"x\\ud800\"} /-->");
        Block back = WpBlocks.parse(doc).get(0);
        assertThat(back.attributesValid()).isFalse();
        assertThat(back.attributes().isEmpty()).isTrue();
    }
}
