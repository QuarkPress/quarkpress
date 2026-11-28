package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wporacle.OracleJson;

class PhpTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private record Row(String label, JsonNode value, boolean empty, boolean valid) {}

    private static JsonNode json(String text) {
        try {
            return OracleJson.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    // SE11, H3, G195: PHP empty() and is_valid_style_value differ only on the string "0"
    @Test
    void emptinessTablesDiffer() {
        List<Row> rows = List.of(
                // G195, in its order
                new Row("\"0\"", json("\"0\""), true, true),
                new Row("0", json("0"), true, false),
                new Row("\"\"", json("\"\""), true, false),
                new Row("\" \"", json("\" \""), false, true),
                new Row("null", json("null"), true, false),
                new Row("false", json("false"), true, false),
                new Row("\"00\"", json("\"00\""), false, true),
                new Row("0.0", json("0.0"), true, false),
                new Row("\"0.0\"", json("\"0.0\""), false, true),
                new Row("[]", json("[]"), true, false),
                new Row("[\"x\"]", json("[\"x\"]"), false, true),
                new Row("\"a\"", json("\"a\""), false, true),
                // more shapes
                new Row("-0.0", json("-0.0"), true, false),
                new Row("0L", NODES.numberNode(0L), true, false),
                new Row("0 bigint", NODES.numberNode(BigInteger.ZERO), true, false),
                new Row("1", json("1"), false, true),
                new Row("true", json("true"), false, true),
                new Row("{}", json("{}"), true, false),
                new Row("{\"a\":1}", json("{\"a\":1}"), false, true),
                new Row("\"false\"", json("\"false\""), false, true),
                new Row("missing", MissingNode.getInstance(), true, false),
                new Row("NullNode", NODES.nullNode(), true, false),
                new Row("Java null", null, true, false));
        List<String> wrong = new ArrayList<>();
        for (Row row : rows) {
            if (Php.isEmpty(row.value()) != row.empty()) {
                wrong.add("isEmpty " + row.label());
            }
            if (Php.isTruthy(row.value()) == row.empty()) {
                wrong.add("isTruthy " + row.label());
            }
            if (Php.isValidStyleValue(row.value()) != row.valid()) {
                wrong.add("isValidStyleValue " + row.label());
            }
            if (row.value() != null
                    && row.value().isTextual()
                    && Php.isEmpty(row.value().asText()) != row.empty()) {
                wrong.add("isEmpty(String) " + row.label());
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(Php.isEmpty((String) null)).isTrue();
    }

    @Test
    void arraysAndStrictTrue() {
        assertThat(Php.isArray(json("[]"))).isTrue();
        assertThat(Php.isArray(json("{}"))).isTrue();
        assertThat(Php.isArray(json("\"a\""))).isFalse();
        assertThat(Php.isArray(null)).isFalse();
        assertThat(Php.isStrictTrue(json("true"))).isTrue();
        assertThat(Php.isStrictTrue(json("1"))).isFalse();
        assertThat(Php.isStrictTrue(json("\"1\""))).isFalse();
        assertThat(Php.isStrictTrue(null)).isFalse();
    }

    // H3: trim removes space, TAB, LF, CR, NUL and VT, but not FF
    @Test
    void trimSet() {
        assertThat(Php.trim("\f x \u000b\u0000")).isEqualTo("\f x");
        assertThat(Php.trim(" \t\n\r\u0000\u000b")).isEmpty();
        assertThat(Php.trim("\u00a0x\u00a0")).isEqualTo("\u00a0x\u00a0");
        assertThat(Php.rtrim(" x \t\n")).isEqualTo(" x");
        assertThat(Php.rtrim("x\f")).isEqualTo("x\f");
    }

    // SE10: lookup in lists by canonical decimal index only
    @Test
    void getOnArrays() {
        assertThat(Php.get(json("[\"a\",\"b\"]"), "1")).isEqualTo(NODES.textNode("b"));
        assertThat(Php.get(json("[\"a\"]"), "01")).isNull();
        assertThat(Php.get(json("[\"a\"]"), "-0")).isNull();
        assertThat(Php.get(json("[\"a\"]"), "1")).isNull();
        assertThat(Php.get(json("[\"a\"]"), "text")).isNull();
        assertThat(Php.get(json("\"str\""), "x")).isNull();
        assertThat(Php.get(null, "x")).isNull();
        assertThat(Php.get(json("{\"a\":null}"), "a")).isEqualTo(NODES.nullNode());
        assertThat(Php.get(json("{\"a\":null}"), "b")).isNull();
    }

    @Test
    void entriesInOrder() {
        List<String> seen = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : Php.entries(json("{\"b\":1,\"a\":2}"))) {
            seen.add(e.getKey() + "=" + e.getValue());
        }
        for (Map.Entry<String, JsonNode> e : Php.entries(json("[\"x\",\"y\"]"))) {
            seen.add(e.getKey() + "=" + e.getValue().asText());
        }
        assertThat(seen).containsExactly("b=1", "a=2", "0=x", "1=y");
        assertThat(Php.entries(json("\"s\""))).isEmpty();
    }

    @Test
    void toPhpString() {
        assertThat(Php.toPhpString(json("5"))).isEqualTo("5");
        assertThat(Php.toPhpString(json("-3"))).isEqualTo("-3");
        assertThat(Php.toPhpString(json("true"))).isEqualTo("1");
        assertThat(Php.toPhpString(json("false"))).isEmpty();
        assertThat(Php.toPhpString(json("null"))).isEmpty();
        assertThat(Php.toPhpString(null)).isEmpty();
        assertThat(Php.toPhpString(json("\"x\""))).isEqualTo("x");
        assertThat(Php.toPhpString(json("[1]"))).isEqualTo("Array");
        assertThat(Php.toPhpString(json("{}"))).isEqualTo("Array");
        assertThat(Php.toPhpString(json("12345678901234567890"))).isEqualTo("1.2345678901235E+19");
    }

    // PHP's string cast of a float: 14 significant digits; the t1-phpstring-* parity cases fix the shapes
    @Test
    void floatsAsPhpCastsThem() {
        Map<String, String> casts = Map.ofEntries(
                Map.entry("1.5", "1.5"),
                Map.entry("1.0", "1"),
                Map.entry("0.1", "0.1"),
                Map.entry("1e25", "1.0E+25"),
                Map.entry("-0.0", "-0"),
                Map.entry("1e14", "1.0E+14"),
                Map.entry("1e13", "10000000000000"),
                Map.entry("0.0001", "0.0001"),
                Map.entry("0.00001", "1.0E-5"),
                Map.entry("-2.5e20", "-2.5E+20"),
                Map.entry("0.30000000000000004", "0.3"),
                Map.entry("0.6666666666666666", "0.66666666666667"),
                Map.entry("12345678901234.5", "12345678901234"),
                Map.entry("1234567890123.45", "1234567890123.4"),
                Map.entry("99999999999999.5", "1.0E+14"),
                Map.entry("5e-324", "4.9406564584125E-324"),
                Map.entry("1.7976931348623157e308", "1.7976931348623E+308"));
        casts.forEach((in, out) -> assertThat(Php.toPhpString(json(in))).as(in).isEqualTo(out));
        assertThat(Php.toPhpString(NODES.numberNode(Double.POSITIVE_INFINITY))).isEqualTo("INF");
        assertThat(Php.toPhpString(NODES.numberNode(Double.NEGATIVE_INFINITY))).isEqualTo("-INF");
        assertThat(Php.toPhpString(NODES.numberNode(Double.NaN))).isEqualTo("NAN");
    }
}
