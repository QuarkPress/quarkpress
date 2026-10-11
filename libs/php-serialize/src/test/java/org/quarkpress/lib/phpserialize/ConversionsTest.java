package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
