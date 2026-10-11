package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhpWriterTest {

    static String rt(String in) {
        return new String(PhpSerialize.serialize(PhpSerialize.parse(in)), ISO_8859_1);
    }

    @Test
    void writesScalars() {
        assertThat(PhpSerialize.serializeToString(PhpNull.INSTANCE)).isEqualTo("N;");
        assertThat(PhpSerialize.serializeToString(PhpBool.TRUE)).isEqualTo("b:1;");
        assertThat(PhpSerialize.serializeToString(PhpBool.FALSE)).isEqualTo("b:0;");
        assertThat(PhpSerialize.serializeToString(new PhpInt(-7))).isEqualTo("i:-7;");
        assertThat(PhpSerialize.serializeToString(new PhpInt(Long.MIN_VALUE))).isEqualTo("i:-9223372036854775808;");
    }

    @Test
    void writesFloats() {
        assertThat(PhpSerialize.serializeToString(new PhpFloat(0.1))).isEqualTo("d:0.1;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(100.0))).isEqualTo("d:100;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(-0.0))).isEqualTo("d:-0;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(1e25))).isEqualTo("d:1.0E25;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(Double.POSITIVE_INFINITY)))
                .isEqualTo("d:INF;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(Double.NEGATIVE_INFINITY)))
                .isEqualTo("d:-INF;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(Double.NaN))).isEqualTo("d:NAN;");
        assertThat(PhpSerialize.serializeToString(new PhpFloat(1.5, "1.50"))).isEqualTo("d:1.50;");
    }

    @Test
    void writesStringsWithByteLengths() {
        assertThat(PhpSerialize.serializeToString(PhpString.of("é"))).isEqualTo("s:2:\"Ã©\";");
        assertThat(PhpSerialize.serializeToString(PhpString.of("a\";b"))).isEqualTo("s:4:\"a\";b\";");
        assertThat(PhpSerialize.serialize(PhpString.of(""))).isEqualTo("s:0:\"\";".getBytes(UTF_8));
    }

    @Test
    void writesCompound() {
        PhpValue v = PhpArray.builder()
                .put("k", PhpArray.of(new PhpInt(1)))
                .put(
                        5,
                        new PhpObject(
                                "Foo",
                                PhpArray.builder().put("a", PhpBool.FALSE).build()))
                .build();
        assertThat(PhpSerialize.serializeToString(v))
                .isEqualTo("a:2:{s:1:\"k\";a:1:{i:0;i:1;}i:5;O:3:\"Foo\":1:{s:1:\"a\";b:0;}}");
        assertThat(PhpSerialize.serializeToString(new PhpCustomObject("Foo", "hello".getBytes(UTF_8))))
                .isEqualTo("C:3:\"Foo\":5:{hello}");
        assertThat(PhpSerialize.serializeToString(new PhpReference(2, PhpReference.Kind.REFERENCE)))
                .isEqualTo("R:2;");
        assertThat(PhpSerialize.serializeToString(new PhpReference(3, PhpReference.Kind.OBJECT)))
                .isEqualTo("r:3;");
        assertThat(PhpSerialize.serializeToString(new PhpEnum("Suit", "Hearts")))
                .isEqualTo("E:11:\"Suit:Hearts\";");
        assertThat(PhpSerialize.serializeToString(PhpArray.empty())).isEqualTo("a:0:{}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "N;",
                "b:0;",
                "i:0;",
                "d:1.0E+25;",
                "d:-0.5;",
                "d:INF;",
                "d:100;",
                "s:0:\"\";",
                "s:4:\"a\";b\";",
                "a:0:{}",
                "a:1:{s:9:\"sidebar-1\";a:2:{i:0;s:7:\"block-2\";i:1;s:7:\"block-3\";}}",
                "O:8:\"stdClass\":1:{s:3:\"foo\";i:1;}",
                "O:3:\"Foo\":1:{s:8:\"\0Foo\0prv\";i:2;}",
                "C:3:\"Foo\":5:{hello}",
                "a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}",
                "a:2:{i:0;i:5;i:1;R:2;}",
                "E:11:\"Suit:Hearts\";"
            })
    void roundTripsByteIdentically(String in) {
        assertThat(rt(in)).isEqualTo(in);
    }

    @Test
    void roundTripsNonUtf8BytesIdentically() {
        byte[] in = "s:3:\"éÿ\u0000\";".getBytes(ISO_8859_1);
        assertThat(PhpSerialize.serialize(PhpSerialize.parse(in))).isEqualTo(in);
    }
}
