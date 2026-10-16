package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversionsTest {

    @Test
    void listArrayBecomesList() {
        assertThat(PhpSerialize.parse("a:2:{i:0;i:1;i:1;s:1:\"x\";}").toJavaObject())
                .isEqualTo(List.of(1L, "x"));
    }

    @Test
    void emptyArrayBecomesEmptyList() {
        assertThat(PhpArray.empty().toJavaObject()).isEqualTo(List.of());
    }

    @Test
    void listMayContainNulls() {
        assertThat(PhpSerialize.parse("a:1:{i:0;N;}").toJavaObject()).isEqualTo(java.util.Arrays.asList((Object) null));
    }

    @Test
    void assocArrayBecomesOrderedMapWithStringKeys() {
        Object o = PhpSerialize.parse("a:2:{s:1:\"b\";i:1;i:7;N;}").toJavaObject();
        assertThat(o).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        assertThat(m).containsExactly(entry("b", (Object) 1L), entry("7", null));
    }

    @Test
    void objectBecomesMapWithUnmangledPropertyNames() {
        Object o = PhpSerialize.parse("O:3:\"Foo\":3:{s:3:\"pub\";i:1;s:8:\"\0Foo\0prv\";i:2;s:6:\"\0*\0pro\";i:3;}")
                .toJavaObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        assertThat(m).containsExactly(entry("pub", (Object) 1L), entry("prv", (Object) 2L), entry("pro", (Object) 3L));
    }

    @Test
    void scalarsAndOthers() {
        assertThat(PhpNull.INSTANCE.toJavaObject()).isNull();
        assertThat(PhpBool.TRUE.toJavaObject()).isEqualTo(true);
        assertThat(new PhpInt(3).toJavaObject()).isEqualTo(3L);
        assertThat(new PhpFloat(1.5).toJavaObject()).isEqualTo(1.5);
        assertThat(PhpString.of("hi").toJavaObject()).isEqualTo("hi");
        assertThat(new PhpEnum("Suit", "Hearts").toJavaObject()).isEqualTo("Suit:Hearts");
        assertThat(new PhpCustomObject("Foo", "x".getBytes(UTF_8)).toJavaObject())
                .isEqualTo("x");
        assertThat(new PhpReference(1, PhpReference.Kind.OBJECT).toJavaObject()).isNull();
    }

    @Test
    void toJsonOfMixedDocument() {
        String json = PhpSerialize.parse(
                        "a:3:{s:1:\"a\";a:2:{i:0;i:1;i:1;d:0.5;}s:1:\"b\";s:5:\"q\"\\\n\u0001\";s:1:\"c\";b:1;}")
                .toJson();
        assertThat(json).isEqualTo("{\"a\":[1,0.5],\"b\":\"q\\\"\\\\\\n\\u0001\",\"c\":true}");
    }

    @Test
    void toJsonEdgeCases() {
        assertThat(PhpArray.empty().toJson()).isEqualTo("[]");
        assertThat(PhpNull.INSTANCE.toJson()).isEqualTo("null");
        assertThat(new PhpFloat(Double.NaN).toJson()).isEqualTo("null");
        assertThat(new PhpFloat(2.0).toJson()).isEqualTo("2");
        assertThat(new PhpFloat(1e25).toJson()).isEqualTo("1.0E25");
        assertThat(new PhpInt(-5).toJson()).isEqualTo("-5");
        assertThat(PhpString.of("é\t").toJson()).isEqualTo("\"é\\t\"");
        assertThat(new PhpObject(
                                "Foo",
                                PhpArray.builder().put("x", PhpNull.INSTANCE).build())
                        .toJson())
                .isEqualTo("{\"x\":null}");
        assertThat(new PhpObject("Foo", PhpArray.empty()).toJson()).isEqualTo("{}");
        assertThat(new PhpEnum("Suit", "Hearts").toJson()).isEqualTo("\"Suit:Hearts\"");
        assertThat(new PhpReference(1, PhpReference.Kind.OBJECT).toJson()).isEqualTo("null");
        assertThat(PhpArray.builder().put(3, PhpBool.FALSE).build().toJson()).isEqualTo("{\"3\":false}");
    }

    @Test
    void referencesResolveToCopiesOfTheirTarget() { // #14
        assertThat(PhpSerialize.parse("a:2:{i:0;a:1:{i:0;i:1;}i:1;R:2;}").toJavaObject())
                .isEqualTo(List.of(List.of(1L), List.of(1L)));
        assertThat(PhpSerialize.parse("a:2:{i:0;a:1:{i:0;i:1;}i:1;R:2;}").toJson())
                .isEqualTo("[[1],[1]]");
        assertThat(PhpSerialize.parse("a:2:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:7;}i:1;r:2;}")
                        .toJson())
                .isEqualTo("[{\"p\":7},{\"p\":7}]");
        assertThat(PhpSerialize.parse("a:3:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;i:2;r:3;}")
                        .toJson())
                .isEqualTo("[{},{},{}]");
        assertThat(PhpSerialize.parse("a:3:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;i:2;R:3;}")
                        .toJson())
                .isEqualTo("[{},{},{}]");
        assertThat(PhpSerialize.parse("a:2:{s:1:\"x\";i:1;s:1:\"y\";R:2;}").toJson())
                .isEqualTo("{\"x\":1,\"y\":1}");
    }

    @Test
    void resolvedCopiesAreIndependent() {
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>)
                PhpSerialize.parse("a:2:{i:0;a:1:{i:0;i:1;}i:1;R:2;}").toJavaObject();
        assertThat(list.get(0)).isEqualTo(list.get(1)).isNotSameAs(list.get(1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"a:1:{i:0;R:1;}", "O:8:\"stdClass\":1:{s:1:\"a\";r:1;}", "a:1:{s:1:\"a\";a:1:{s:1:\"b\";R:1;}}"})
    void referenceCyclesThrow(String in) { // #14
        PhpValue value = PhpSerialize.parse(in);
        assertThatThrownBy(value::toJavaObject)
                .isInstanceOf(PhpSerializeException.class)
                .hasMessage("cyclic reference");
        assertThatThrownBy(value::toJson)
                .isInstanceOf(PhpSerializeException.class)
                .hasMessage("cyclic reference");
    }

    @Test
    void referenceExpansionIsBounded() {
        // element k holds two R: references to element k-1, so element 25 stands for 2^24 copies of element 1
        StringBuilder doc = new StringBuilder("a:26:{i:0;a:1:{i:0;i:1;}");
        int previous = 2; // slot of element 0; its i:1 takes slot 3
        for (int k = 1; k <= 25; k++) {
            doc.append("i:")
                    .append(k)
                    .append(";a:2:{i:0;R:")
                    .append(previous)
                    .append(";i:1;R:")
                    .append(previous)
                    .append(";}");
            previous = 3 + k; // element k takes the next slot; its R: entries take none
        }
        doc.append('}');
        PhpValue value = PhpSerialize.parse(doc.toString());
        assertThatThrownBy(value::toJavaObject)
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("expand");
    }

    @Test
    void danglingReferenceInAHandBuiltTreeReadsAsNull() {
        PhpArray handBuilt = PhpArray.of(new PhpInt(1), new PhpReference(9, PhpReference.Kind.REFERENCE));
        assertThat(handBuilt.toJavaObject()).isEqualTo(java.util.Arrays.asList(1L, null));
        assertThat(handBuilt.toJson()).isEqualTo("[1,null]");
    }

    @Test
    void toJsonUsesVisiblePropertyNames() { // #27
        assertThat(PhpSerialize.parse("O:3:\"Foo\":3:{s:3:\"pub\";i:1;s:8:\"\0Foo\0prv\";i:2;s:6:\"\0*\0pro\";i:3;}")
                        .toJson())
                .isEqualTo("{\"pub\":1,\"prv\":2,\"pro\":3}");
        PhpValue publicLast = PhpSerialize.parse("O:3:\"Foo\":2:{s:6:\"\0Foo\0a\";i:1;s:1:\"a\";i:2;}");
        assertThat(publicLast.toJson()).isEqualTo("{\"a\":2}");
        assertThat(publicLast.toJavaObject()).isEqualTo(Map.of("a", 2L));
        PhpValue publicFirst = PhpSerialize.parse("O:3:\"Foo\":2:{s:1:\"a\";i:2;s:4:\"\0*\0a\";i:1;}");
        assertThat(publicFirst.toJson()).isEqualTo("{\"a\":2}");
        PhpValue twoHidden = PhpSerialize.parse("O:3:\"Foo\":2:{s:6:\"\0Foo\0a\";i:1;s:4:\"\0*\0a\";i:2;}");
        assertThat(twoHidden.toJson()).isEqualTo("{\"a\":1}");
    }

    @Test
    void longChainsOfObjectHandlesResolveWithoutRecursion() {
        // PHP: every r: in the chain is the same object; json_encode gives [{},{},{},{}] for three handles
        assertThat(PhpSerialize.parse("a:4:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;i:2;r:3;i:3;r:4;}")
                        .toJson())
                .isEqualTo("[{},{},{},{}]");
        int n = 20_000;
        StringBuilder doc = new StringBuilder("a:").append(n).append(":{i:0;O:8:\"stdClass\":0:{}");
        for (int k = 1; k < n; k++) {
            doc.append("i:").append(k).append(";r:").append(k + 1).append(';');
        }
        doc.append('}');
        PhpValue value = PhpSerialize.parse(doc.toString());
        assertThat((List<?>) value.toJavaObject()).hasSize(n);
        assertThat(value.toJson()).startsWith("[{},{},{}").hasSize(2 * n + (n - 1) + 2);
    }

    @Test
    void integerTextAndIntegerKeysNoLongerCollide() { // #28
        assertThat(PhpSerialize.parse("a:2:{s:1:\"1\";i:1;i:1;i:2;}").toJavaObject())
                .isEqualTo(Map.of("1", 2L));
    }
}
