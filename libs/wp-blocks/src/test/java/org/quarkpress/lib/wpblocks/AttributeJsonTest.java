package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
}
