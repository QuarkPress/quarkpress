package org.quarkpress.restwp.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RestJsonTest {

    @Test
    void encodesLikePhp() {
        String text = "Caf" + new String(Character.toChars(0xE9)) + "/Bar <b> & 'q' "
                + new String(Character.toChars(0x1F600));
        assertThat(encoded(object("name", text))).isEqualTo("{\"name\":\"Caf\\u00e9\\/Bar <b> & 'q' \\ud83d\\ude00\"}");
        assertThat(encoded(object("c", new String(Character.toChars(1))))).isEqualTo("{\"c\":\"\\u0001\"}");
        assertThat(encoded(object("c", new String(Character.toChars(0x1F)) + new String(Character.toChars(0xFEFF)))))
                .isEqualTo("{\"c\":\"\\u001f\\ufeff\"}");
    }

    @Test
    void shortEscapesAndDeleteAsPhpWritesThem() {
        // php -r 'echo json_encode(["c" => "\x08\x09\x0a\x0c\x0d\"\\\x7f"]);'
        assertThat(encoded(object("c", "\b\t\n\f\r\"\\\u007f"))).isEqualTo("{\"c\":\"\\b\\t\\n\\f\\r\\\"\\\\\u007f\"}");
        // keys are escaped as values are
        assertThat(encoded(object("a/" + new String(Character.toChars(0xE9)), "x")))
                .isEqualTo("{\"a\\/\\u00e9\":\"x\"}");
    }

    @Test
    void numbersAndEmptiesAsPhpPrintsThem() {
        ObjectNode node = RestJson.NODES.objectNode();
        node.put("f", 10.0).put("h", 0.5).put("s", "5");
        node.putObject("o");
        node.putArray("a");
        assertThat(encoded(node)).isEqualTo("{\"f\":10,\"h\":0.5,\"s\":\"5\",\"o\":{},\"a\":[]}");
    }

    @Test
    void doublesFollowPhpsSerializePrecision() {
        // php -r 'echo json_encode([1e16, 1e17, 1e-4, 1e-5, -2.5e20, 0.1 + 0.2, -0.0, 5e-324,
        // 1.7976931348623157e308, 1.5e-5, 123.456, 1e-323, 2.5e-323]);'
        assertThat(new String(
                        RestJson.encode(
                                json("[1e16,1e17,1e-4,1e-5,-2.5e20,0.30000000000000004,-0.0,5e-324,"
                                        + "1.7976931348623157e308,1.5e-5,123.456,1e-323,2.5e-323]"),
                                false),
                        UTF_8))
                .isEqualTo("[10000000000000000,1.0e+17,0.0001,1.0e-5,-2.5e+20,0.30000000000000004,-0,5.0e-324,"
                        + "1.7976931348623157e+308,1.5e-5,123.456,1.0e-323,2.5e-323]");
    }

    @Test
    void readsKeepKeyOrderAndIntegersAsExactLongs() {
        JsonNode node = json("{\"z\":1,\"m\":9007199254740993,\"d\":0.1,\"x\":-9223372036854775808}");
        assertThat(node.fieldNames()).toIterable().containsExactly("z", "m", "d", "x");
        assertThat(node.get("z").isLong()).isTrue();
        assertThat(node.get("m").isLong()).isTrue();
        assertThat(node.get("m").longValue()).isEqualTo(9007199254740993L);
        assertThat(node.get("d").isDouble()).isTrue();
        assertThat(node.get("x").isLong()).isTrue();
        assertThat(node.get("x").longValue()).isEqualTo(Long.MIN_VALUE);
        assertThat(encoded(node)).isEqualTo("{\"z\":1,\"m\":9007199254740993,\"d\":0.1,\"x\":-9223372036854775808}");
        assertThat(RestJson.NODES.objectNode().put("i", 5).get("i").isLong()).isTrue();
    }

    @Test
    void integersBeyondTheLongRangeReadAsDoublesAsJsonDecodeReadsThem() {
        // php -r 'echo json_encode(json_decode("[12345678901234567890123,9223372036854775807,9223372036854775808,
        // -9223372036854775809]"));'
        JsonNode node = json("[12345678901234567890123,9223372036854775807,9223372036854775808,-9223372036854775809]");
        assertThat(node.get(0).isDouble()).isTrue();
        assertThat(node.get(0).doubleValue()).isEqualTo(1.2345678901234568e22);
        assertThat(node.get(1).isLong()).isTrue();
        assertThat(node.get(2).isDouble()).isTrue();
        assertThat(node.get(3).isDouble()).isTrue();
        assertThat(encoded(node))
                .isEqualTo("[1.2345678901234568e+22,9223372036854775807,9.223372036854776e+18,-9.223372036854776e+18]");
    }

    @Test
    void bigNumbersBuiltByCodeAreWrittenAsPhpsDoubles() {
        // php -r 'echo json_encode([123456789012345678901234567890, 0.5]);'
        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        node.add(new BigInteger("123456789012345678901234567890"))
                .add(BigInteger.valueOf(42))
                .add(new BigDecimal("0.50"));
        assertThat(encoded(node)).isEqualTo("[1.2345678901234568e+29,42,0.5]");
    }

    @Test
    void nanAndInfinityAreRefusedAsJsonEncodeRefusesThem() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ArrayNode node = RestJson.NODES.arrayNode().add(1).add(value);
            assertThatThrownBy(() -> RestJson.encode(node, false))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Inf and NaN cannot be JSON encoded");
        }
    }

    @Test
    void prettyPrintIsPhps() {
        assertThat(new String(RestJson.encode(json("{\"a\":[1,2],\"b\":{},\"c\":{\"d\":\"x\"}}"), true), UTF_8))
                .isEqualTo("{\n    \"a\": [\n        1,\n        2\n    ],\n    \"b\": {},\n"
                        + "    \"c\": {\n        \"d\": \"x\"\n    }\n}");
        // php -r 'echo json_encode([[], ["k" => "/"]], JSON_PRETTY_PRINT);'
        assertThat(new String(RestJson.encode(json("[[],{\"k\":\"/\"}]"), true), UTF_8))
                .isEqualTo("[\n    [],\n    {\n        \"k\": \"\\/\"\n    }\n]");
        assertThat(new String(RestJson.encode(json("[]"), true), UTF_8)).isEqualTo("[]");
    }

    private static ObjectNode object(String key, String value) {
        return RestJson.NODES.objectNode().put(key, value);
    }

    private static String encoded(JsonNode node) {
        return new String(RestJson.encode(node, false), UTF_8);
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
