package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PhpParserScalarsTest {

    @Test
    void parsesNull() {
        assertThat(PhpSerialize.parse("N;")).isSameAs(PhpNull.INSTANCE);
    }

    @Test
    void parsesBooleans() {
        assertThat(PhpSerialize.parse("b:1;")).isEqualTo(PhpBool.TRUE);
        assertThat(PhpSerialize.parse("b:0;")).isEqualTo(PhpBool.FALSE);
        assertThatThrownBy(() -> PhpSerialize.parse("b:2;")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void parsesIntegersIncludingNegativeAndLongRange() {
        assertThat(PhpSerialize.parse("i:-42;")).isEqualTo(new PhpInt(-42));
        assertThat(PhpSerialize.parse("i:9223372036854775807;")).isEqualTo(new PhpInt(Long.MAX_VALUE));
        assertThatThrownBy(() -> PhpSerialize.parse("i:9223372036854775808;"))
                .isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("i:;")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void parsesFloatsKeepingRawLexeme() {
        assertThat(PhpSerialize.parse("d:0.1;")).isEqualTo(new PhpFloat(0.1));
        assertThat(((PhpFloat) PhpSerialize.parse("d:1.0E+25;")).raw()).isEqualTo("1.0E+25");
        assertThat(((PhpFloat) PhpSerialize.parse("d:1.0E+25;")).value()).isEqualTo(1.0e25);
        assertThat(((PhpFloat) PhpSerialize.parse("d:100;")).value()).isEqualTo(100.0);
        assertThat(((PhpFloat) PhpSerialize.parse("d:INF;")).value()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(((PhpFloat) PhpSerialize.parse("d:-INF;")).value()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(((PhpFloat) PhpSerialize.parse("d:NAN;")).value()).isNaN();
    }

    @Test
    void rejectsJavaOnlyFloatSyntax() {
        assertThatThrownBy(() -> PhpSerialize.parse("d:0x1p3;")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("d:Infinity;")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("d:1d;")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("d:;")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void parsesStrings() {
        assertThat(PhpSerialize.parse("s:5:\"hello\";")).isEqualTo(PhpString.of("hello"));
        assertThat(PhpSerialize.parse("s:0:\"\";")).isEqualTo(PhpString.of(""));
    }

    @Test
    void multiByteStringLengthIsBytes() {
        assertThat(((PhpString) PhpSerialize.parse("s:2:\"é\";")).text()).isEqualTo("é");
    }

    @Test
    void strictModeRejectsCharCountLengthForMultiByteString() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:1:\"é\";")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void stringMayContainQuotesAndSemicolons() {
        assertThat(((PhpString) PhpSerialize.parse("s:4:\"a\";b\";")).text()).isEqualTo("a\";b");
    }

    @Test
    void latin1CharsetDecodesLegacyBytes() {
        byte[] in = "s:1:\"é\";".getBytes(StandardCharsets.ISO_8859_1);
        PhpString s =
                (PhpString) PhpSerialize.parse(in, ParseOptions.DEFAULTS.withCharset(StandardCharsets.ISO_8859_1));
        assertThat(s.text()).isEqualTo("é");
        assertThat(s.byteLength()).isEqualTo(1);
    }

    @Test
    void stringInputIsEncodedWithOptionsCharset() {
        PhpString s = (PhpString)
                PhpSerialize.parse("s:1:\"é\";", ParseOptions.DEFAULTS.withCharset(StandardCharsets.ISO_8859_1));
        assertThat(s.text()).isEqualTo("é");
    }

    @Test
    void strictLengthMismatchThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:3:\"hello\";")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("s:-1:\"\";")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void truncatedInputThrowsWithOffset() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:5:\"hel"))
                .isInstanceOfSatisfying(
                        PhpSerializeException.class, e -> assertThat(e.offset()).isEqualTo(2));
        assertThatThrownBy(() -> PhpSerialize.parse("i:12")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse(new byte[0])).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void trailingGarbageThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("i:1;x"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("offset 4");
    }

    @Test
    void declaredStringLengthBeyondLimitFails() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:999999999:\"x\";"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(() -> PhpSerialize.parse("s:5:\"hello\";", ParseOptions.DEFAULTS.withMaxStringBytes(4)))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("length");
    }

    @Test
    void unknownTypeTagThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("Z:1;")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void exceptionMessageIncludesOffset() {
        assertThatThrownBy(() -> PhpSerialize.parse("i:x;")).isInstanceOfSatisfying(PhpSerializeException.class, e -> {
            assertThat(e.getMessage()).contains("offset");
            assertThat(e.offset()).isEqualTo(2);
        });
    }
}
