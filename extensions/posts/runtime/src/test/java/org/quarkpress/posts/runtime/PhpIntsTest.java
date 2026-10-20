package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected values were produced by PHP 8.4's {@code (int)} cast on the same values. */
class PhpIntsTest {

    @Test
    void textIsCastAsPhpCastsIt() {
        Map<String, Long> cases = new LinkedHashMap<>();
        cases.put("12abc", 12L);
        cases.put(" 12", 12L);
        cases.put("\t\n12", 12L);
        cases.put("12 ", 12L);
        cases.put("+12", 12L);
        cases.put("012", 12L);
        cases.put("12.9", 12L);
        cases.put("1.2e1", 12L);
        cases.put("1e3abc", 1000L);
        cases.put("-12", -12L);
        cases.put("0x1A", 0L);
        cases.put(".5", 0L);
        cases.put("5.", 5L);
        cases.put("1e", 1L);
        cases.put("1e+", 1L);
        cases.put("-.5e1", -5L);
        cases.put("9999999999999999999", Long.MAX_VALUE);
        cases.put("-9999999999999999999", Long.MIN_VALUE);
        cases.put("1e30", Long.MAX_VALUE);
        cases.put("-1e30", Long.MIN_VALUE);
        cases.put("1e400", 0L);
        cases.put("abc", 0L);
        cases.put("", 0L);
        cases.put(" ", 0L);
        cases.put("\u000b12", 12L);
        cases.put("\f12", 12L);
        cases.put("\r12", 12L);
        cases.put("12e", 12L);
        cases.put("+-1", 0L);
        cases.put("  -0012.99e0x", -12L);
        cases.put("1.9999999999999999999", 2L);
        cases.put("NAN", 0L);
        cases.put("INF", 0L);
        cases.put("9223372036854775807", Long.MAX_VALUE);
        cases.put("9223372036854775808", Long.MAX_VALUE);
        cases.put("1e18", 1_000_000_000_000_000_000L);
        cases.put("9.2233720368547758E18", Long.MAX_VALUE);
        cases.put(" +7 ", 7L);
        cases.put("9007199254740993", 9_007_199_254_740_993L);
        cases.forEach((text, expected) -> {
            assertThat(PhpInts.of(text)).as("(int) %s", text).isEqualTo(expected);
            assertThat(PhpInts.of(TextNode.valueOf(text))).as("(int) %s", text).isEqualTo(expected);
        });
    }

    @Test
    void otherValuesAreCastAsPhpCastsThem() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        Map<JsonNode, Long> cases = new LinkedHashMap<>();
        cases.put(IntNode.valueOf(12), 12L);
        cases.put(LongNode.valueOf(-5_000_000_000L), -5_000_000_000L);
        cases.put(DoubleNode.valueOf(12.0), 12L);
        cases.put(DoubleNode.valueOf(12.9), 12L);
        cases.put(DoubleNode.valueOf(-12.9), -12L);
        cases.put(DoubleNode.valueOf(1e30), 5_076_964_154_930_102_272L);
        cases.put(DoubleNode.valueOf(Double.NaN), 0L);
        cases.put(DoubleNode.valueOf(Double.POSITIVE_INFINITY), 0L);
        cases.put(BooleanNode.TRUE, 1L);
        cases.put(BooleanNode.FALSE, 0L);
        cases.put(NullNode.instance, 0L);
        cases.put(json.arrayNode(), 0L);
        cases.put(json.arrayNode().add(1), 1L);
        cases.put(json.objectNode(), 0L);
        cases.put(json.objectNode().put("a", 1), 1L);
        cases.forEach((value, expected) ->
                assertThat(PhpInts.of(value)).as("(int) %s", value).isEqualTo(expected));
        assertThat(PhpInts.of((JsonNode) null)).isZero();
    }
}
