package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

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
}
