package org.quarkpress.persistence.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class JsonValuesTest {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    // Built from code points: the formatter turns printable escapes into literal characters.
    private static final char E_ACUTE = (char) 0xE9;

    /** Reads JSON like QuarkPress's persistence unit does. */
    private static final ObjectMapper STORE = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    static JsonNode nested(int depth) {
        JsonNode value = TextNode.valueOf("leaf");
        for (int i = 0; i < depth; i++) {
            ArrayNode array = F.arrayNode();
            array.add(value);
            value = array;
        }
        return value;
    }

    @Test
    void acceptsPlainJson() {
        JsonNode value = F.objectNode()
                .put("a", 1)
                .put("b", "caf" + E_ACUTE)
                .set("c", F.arrayNode().add(true));
        assertThat(JsonValues.requireStorable(value)).isEqualTo(value).isNotSameAs(value);
        assertThat(JsonValues.requireStorable(LongNode.valueOf(Long.MIN_VALUE)))
                .isEqualTo(LongNode.valueOf(Long.MIN_VALUE));
    }

    @Test
    void acceptsJsonNullInsideArraysAndObjects() {
        JsonNode value = F.objectNode().putNull("a").set("b", F.arrayNode().addNull());
        assertThat(JsonValues.requireStorable(value)).isEqualTo(value);
        assertThat(JsonValues.sameValue(value, value.deepCopy())).isTrue();
    }

    @Test
    void rejectsJsonNullAndMissing() {
        assertThatThrownBy(() -> JsonValues.requireStorable(NullNode.getInstance()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON null");
        assertThatThrownBy(() -> JsonValues.requireStorable(MissingNode.getInstance()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON null");
    }

    @Test
    void acceptsNulInStringsAndKeys() {
        assertThat(JsonValues.requireStorable(TextNode.valueOf("a\u0000b"))).isEqualTo(TextNode.valueOf("a\u0000b"));
        JsonNode key = F.objectNode().put("k\u0000", 1);
        assertThat(JsonValues.requireStorable(key)).isEqualTo(key);
    }

    @Test
    void rejectsLoneSurrogates() {
        assertThatThrownBy(() -> JsonValues.requireStorable(TextNode.valueOf("\uD800")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> JsonValues.requireStorable(TextNode.valueOf("a\uDC00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> JsonValues.requireStorable(F.objectNode().put("\uD83D", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
    }

    @Test
    void integersMustFitInSixtyFourBits() {
        assertThat(JsonValues.requireStorable(BigIntegerNode.valueOf(BigInteger.valueOf(Long.MAX_VALUE))))
                .isEqualTo(LongNode.valueOf(Long.MAX_VALUE));
        assertThatThrownBy(() -> JsonValues.requireStorable(BigIntegerNode.valueOf(BigInteger.TWO.pow(64))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bits");
        assertThatThrownBy(() -> JsonValues.requireStorable(BigIntegerNode.valueOf(BigInteger.TWO.pow(63))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bits");
        assertThatThrownBy(
                        () -> JsonValues.requireStorable(DecimalNode.valueOf(new BigDecimal("18446744073709551616"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 bits");
    }

    @Test
    void nestingIsLimited() {
        assertThat(JsonValues.requireStorable(nested(64))).isEqualTo(nested(64));
        assertThatThrownBy(() -> JsonValues.requireStorable(nested(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
        assertThatThrownBy(() -> JsonValues.requireStorable(nested(100_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        assertThatThrownBy(() -> JsonValues.requireStorable(DoubleNode.valueOf(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> JsonValues.requireStorable(DoubleNode.valueOf(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> JsonValues.requireStorable(FloatNode.valueOf(Float.NEGATIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> JsonValues.requireStorable(DecimalNode.valueOf(new BigDecimal("1e400"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThat(JsonValues.requireStorable(DoubleNode.valueOf(Double.MAX_VALUE)))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("1.7976931348623157E+308")));
    }

    @Test
    void floatingValuesAreStoredAsTheirShortestDecimal() {
        assertThat(JsonValues.requireStorable(DoubleNode.valueOf(0.1)))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("0.1")));
        assertThat(JsonValues.requireStorable(DoubleNode.valueOf(1.0)))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("1.0")));
        assertThat(JsonValues.requireStorable(FloatNode.valueOf(0.1f)))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("0.1")));
        assertThat(JsonValues.requireStorable(ShortNode.valueOf((short) 7))).isEqualTo(IntNode.valueOf(7));
        assertThat(JsonValues.requireStorable(LongNode.valueOf(7))).isEqualTo(IntNode.valueOf(7));
        assertThat(JsonValues.requireStorable(DecimalNode.valueOf(new BigDecimal("100"))))
                .isEqualTo(IntNode.valueOf(100));
    }

    @Test
    void decimalsBelowTheRangeOfADoubleAreRejected() {
        for (String tiny : new String[] {"1e-400", "-1e-400", "1e-325"}) {
            assertThatThrownBy(() -> JsonValues.requireStorable(DecimalNode.valueOf(new BigDecimal(tiny))))
                    .as(tiny)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("range of a double");
        }
        assertThat(JsonValues.requireStorable(DecimalNode.valueOf(new BigDecimal("0E-400"))))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("0E-400")));
        assertThat(JsonValues.requireStorable(DoubleNode.valueOf(Double.MIN_VALUE)))
                .isEqualTo(DecimalNode.valueOf(new BigDecimal("4.9E-324")));
    }

    @Test
    void onlyPlainJsonNodesAreStored() {
        assertThatThrownBy(() -> JsonValues.requireStorable(new POJONode(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only plain JSON");
        assertThatThrownBy(() -> JsonValues.requireStorable(F.arrayNode().add(BinaryNode.valueOf(new byte[] {1}))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only plain JSON");
    }

    @Test
    void valuesTooDeepToCompareCountAsDifferent() {
        assertThat(JsonValues.sameValue(nested(64), nested(64))).isTrue();
        assertThat(JsonValues.sameValue(nested(70), nested(70))).isFalse();
        assertThat(JsonValues.sameValue(nested(100_000), nested(100_000))).isFalse();
    }

    @Test
    void numbersCompareByValue() {
        assertThat(JsonValues.sameValue(IntNode.valueOf(1), LongNode.valueOf(1)))
                .isTrue();
        assertThat(JsonValues.sameValue(IntNode.valueOf(1), DoubleNode.valueOf(1.0)))
                .isTrue();
        assertThat(JsonValues.sameValue(F.arrayNode().add(1), F.arrayNode().add(1L)))
                .isTrue();
        assertThat(JsonValues.sameValue(FloatNode.valueOf(0.1f), DecimalNode.valueOf(new BigDecimal("0.10"))))
                .isTrue();
        assertThat(JsonValues.sameValue(DoubleNode.valueOf(0.1), FloatNode.valueOf(0.1f)))
                .isTrue();
        assertThat(JsonValues.sameValue(DoubleNode.valueOf(Double.NaN), DoubleNode.valueOf(Double.NaN)))
                .isTrue();
        assertThat(JsonValues.sameValue(DoubleNode.valueOf(Double.NaN), IntNode.valueOf(0)))
                .isFalse();
        assertThat(JsonValues.sameValue(IntNode.valueOf(1), TextNode.valueOf("1")))
                .isFalse();
        assertThat(JsonValues.sameValue(IntNode.valueOf(1), IntNode.valueOf(2))).isFalse();
        assertThat(JsonValues.sameValue(
                        F.arrayNode().add(1), F.arrayNode().add(1).add(1)))
                .isFalse();
        assertThat(JsonValues.sameValue(
                        F.objectNode().put("a", 1), F.arrayNode().add(1)))
                .isFalse();
    }

    @Test
    void objectKeyOrderIsPartOfTheValue() {
        JsonNode ab = F.objectNode().put("a", 1).put("b", 2);
        JsonNode ba = F.objectNode().put("b", 2).put("a", 1);
        assertThat(JsonValues.sameValue(ab, ba)).isFalse();
        assertThat(JsonValues.sameValue(ab, F.objectNode().put("a", 1L).put("b", 2.0)))
                .isTrue();
        assertThat(JsonValues.sameValue(ab, F.objectNode().put("a", 1))).isFalse();
        assertThat(JsonValues.sameValue(ab, F.objectNode().put("a", 1).put("c", 2)))
                .isFalse();
        assertThat(JsonValues.requireStorable(ba).toString()).isEqualTo("{\"b\":2,\"a\":1}");
    }

    @Test
    void anyTextIsEitherStoredUnchangedOrRejected() {
        // Mostly ordinary characters (U+0000 included), sometimes a surrogate half that may or may not find its pair.
        Gen<Character> chars = Gens.frequency(
                Gens.weighted(30, Gens.unicodeChars()),
                Gens.weighted(1, Gens.ints(0xD800, 0xDFFF).map(i -> (char) i.intValue())));
        int[] outcomes = {0, 0};
        Properties.check("JsonValuesTest.text", 3000, Gens.strings(chars, 0, 40), text -> {
            try {
                assertThat(JsonValues.requireStorable(TextNode.valueOf(text))).isEqualTo(TextNode.valueOf(text));
                assertThat(hasLoneSurrogate(text)).isFalse();
                outcomes[0]++;
            } catch (IllegalArgumentException rejected) {
                assertThat(rejected).hasMessageContaining("unpaired surrogate");
                assertThat(hasLoneSurrogate(text)).isTrue();
                outcomes[1]++;
            }
        });
        assertThat(outcomes[0]).isPositive();
        assertThat(outcomes[1]).isPositive();
    }

    @Test
    void aStoredNumberReadsBackAsTheCheckedCopy() {
        Gen<JsonNode> numbers = Gens.oneOf(
                Gens.ints(Integer.MIN_VALUE, Integer.MAX_VALUE).map(i -> (JsonNode) IntNode.valueOf(i)),
                Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE).map(l -> (JsonNode) LongNode.valueOf(l)),
                Gens.doubles().filter(Double::isFinite).map(d -> (JsonNode) DoubleNode.valueOf(d)),
                Gens.doubles()
                        .filter(Double::isFinite)
                        .map(d -> (JsonNode) FloatNode.valueOf((float) (double) d))
                        .filter(n -> Float.isFinite(n.floatValue())),
                Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE)
                        .flatMap(unscaled -> Gens.ints(-30, 30)
                                .map(scale -> (JsonNode) DecimalNode.valueOf(BigDecimal.valueOf(unscaled, scale)))));
        int[] checked = {0};
        Properties.check("JsonValuesTest.numbers", 3000, numbers, number -> {
            JsonNode copy;
            try {
                copy = JsonValues.requireStorable(number);
            } catch (IllegalArgumentException rejected) {
                assertThat(rejected).hasMessageContaining("64 bits");
                assertThat(number.isIntegralNumber() || number.decimalValue().scale() <= 0)
                        .isTrue();
                return;
            }
            assertThat(JsonValues.sameValue(copy, number)).isTrue();
            assertThat(readBack(copy)).isEqualTo(copy);
            checked[0]++;
        });
        assertThat(checked[0]).isGreaterThan(2000);
    }

    private static JsonNode readBack(JsonNode value) {
        try {
            return STORE.readTree(STORE.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
