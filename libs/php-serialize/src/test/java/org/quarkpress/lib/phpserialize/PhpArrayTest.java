package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PhpArrayTest {

    @Test
    void listDetectionRequiresSequentialIntKeysFromZero() {
        assertThat(PhpArray.of(new PhpInt(1), new PhpInt(2)).isList()).isTrue();
        assertThat(PhpArray.builder()
                        .put(1, new PhpInt(1))
                        .put(0, new PhpInt(0))
                        .build()
                        .isList())
                .isFalse();
        assertThat(PhpArray.builder().put("a", new PhpInt(1)).build().isList()).isFalse();
        assertThat(PhpArray.empty().isList()).isTrue();
    }

    @Test
    void lookupByStringKeyMatchesBytes() {
        PhpArray a = PhpArray.builder().put("title", PhpString.of("Hello")).build();
        assertThat(a.get("title")).isEqualTo(PhpString.of("Hello"));
        assertThat(a.get("missing")).isNull();
        assertThat(a.get(0)).isNull();
    }

    @Test
    void duplicateKeyKeepsLastValueAndOriginalPosition() {
        PhpArray a = PhpArray.builder()
                .put(0, new PhpInt(1))
                .put(1, new PhpInt(2))
                .put(0, new PhpInt(9))
                .build();
        assertThat(a.values()).containsExactly(new PhpInt(9), new PhpInt(2));
        assertThat(a.size()).isEqualTo(2);
    }

    @Test
    void phpStringEqualityIsByBytes() {
        assertThat(PhpString.of("é")).isEqualTo(PhpString.ofBytes(new byte[] {(byte) 0xC3, (byte) 0xA9}));
        assertThat(PhpString.of("é").byteLength()).isEqualTo(2);
        assertThat(PhpString.of("é").text()).isEqualTo("é");
    }

    @Test
    void phpFloatEqualityIgnoresRawAndTreatsNanEqual() {
        assertThat(new PhpFloat(1.5, "1.5")).isEqualTo(new PhpFloat(1.5));
        assertThat(new PhpFloat(Double.NaN)).isEqualTo(new PhpFloat(Double.NaN));
        assertThat(new PhpFloat(0.0)).isNotEqualTo(new PhpFloat(-0.0));
    }

    @Test
    void customObjectEqualityIsByPayloadContent() {
        assertThat(new PhpCustomObject("Foo", new byte[] {1, 2}))
                .isEqualTo(new PhpCustomObject("Foo", new byte[] {1, 2}));
    }

    @Test
    void arrayIsImmutableSnapshotOfBuilder() {
        PhpArray.Builder b = PhpArray.builder().add(new PhpInt(1));
        PhpArray first = b.build();
        b.add(new PhpInt(2));
        assertThat(first.size()).isEqualTo(1);
        assertThat(b.build().size()).isEqualTo(2);
    }

    @Test
    void phpFloatRejectsRawLexemeThatDoesNotMatchValue() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PhpFloat(2.0, "garbage"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PhpFloat(2.0, "1.5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PhpFloat(1.5, "1.50").raw()).isEqualTo("1.50");
        assertThat(new PhpFloat(Double.POSITIVE_INFINITY, "INF").raw()).isEqualTo("INF");
        assertThat(new PhpFloat(Double.NaN, "NAN").raw()).isEqualTo("NAN");
        assertThat(new PhpFloat(100.0, "100").raw()).isEqualTo("100");
    }

    @Test
    void integerTextKeysBecomeIntegerKeys() { // #10, #12
        PhpArray a = PhpArray.builder()
                .put("5", PhpString.of("a"))
                .put(5, PhpString.of("b"))
                .build();
        assertThat(a.size()).isEqualTo(1);
        assertThat(a.entries().firstEntry().getKey()).isEqualTo(PhpArray.Key.of(5));
        assertThat(a.get(5)).isEqualTo(PhpString.of("b"));
        assertThat(PhpArray.Key.of("-5")).isEqualTo(new PhpArray.Key.Int(-5));
        assertThat(PhpArray.Key.of("0")).isEqualTo(new PhpArray.Key.Int(0));
        assertThat(PhpArray.Key.of("9223372036854775807")).isEqualTo(new PhpArray.Key.Int(Long.MAX_VALUE));
        assertThat(PhpArray.Key.of("-9223372036854775808")).isEqualTo(new PhpArray.Key.Int(Long.MIN_VALUE));
        for (String text : List.of("05", "-0", "+5", " 5", "5 ", "9223372036854775808", "", "-", "1e3")) {
            assertThat(PhpArray.Key.of(text)).as(text).isInstanceOf(PhpArray.Key.Str.class);
        }
    }

    @Test
    void stringKeyRecordRejectsIntegerText() {
        assertThatThrownBy(() -> new PhpArray.Key.Str(PhpString.of("5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key.of");
    }

    @Test
    void textLookupFindsIntegerKeys() { // #11: widget instances are int-keyed, ids arrive as text
        PhpArray widgets = (PhpArray) PhpSerialize.parse("a:2:{i:2;s:1:\"x\";i:3;s:1:\"y\";}");
        assertThat(widgets.get("2")).isEqualTo(PhpString.of("x"));
        assertThat(widgets.get("3")).isEqualTo(PhpString.of("y"));
        assertThat(widgets.get("02")).isNull();
    }

    @Test
    void appendFollowsPhp83() { // #18
        assertThat(PhpSerialize.serializeToString(PhpArray.builder()
                        .put(-5, PhpString.of("a"))
                        .add(PhpString.of("b"))
                        .build()))
                .isEqualTo("a:2:{i:-5;s:1:\"a\";i:-4;s:1:\"b\";}");
        assertThat(keysOf(PhpArray.builder()
                        .put(-5, new PhpInt(1))
                        .put(-10, new PhpInt(2))
                        .add(new PhpInt(3))
                        .build()))
                .containsExactly(-5L, -10L, -4L);
        assertThat(keysOf(PhpArray.builder()
                        .put("x", new PhpInt(1))
                        .add(new PhpInt(2))
                        .build()))
                .containsExactly("x", 0L);
        assertThat(keysOf(PhpArray.builder()
                        .put(-5, new PhpInt(1))
                        .put(3, new PhpInt(2))
                        .add(new PhpInt(3))
                        .build()))
                .containsExactly(-5L, 3L, 4L);
        PhpArray.Builder full = PhpArray.builder().put(Long.MAX_VALUE, PhpNull.INSTANCE);
        assertThatThrownBy(() -> full.add(PhpNull.INSTANCE)).isInstanceOf(IllegalStateException.class);
    }

    private static List<Object> keysOf(PhpArray array) {
        return array.entries().keySet().stream()
                .map(k -> switch (k) {
                    case PhpArray.Key.Int i -> (Object) i.value();
                    case PhpArray.Key.Str s -> s.value().text();
                })
                .toList();
    }
}
