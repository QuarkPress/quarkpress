package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WordPressSerializationTest {

    /** is_serialized($s), is_serialized($s, false) and is_serialized_string($s) in WordPress 7.1.3. */
    record Detection(String input, boolean strict, boolean loose, boolean serializedString) {}

    static Detection[] detections() {
        return new Detection[] {
            new Detection("a:1:{i:0;i:1;}", true, true, false),
            new Detection("i:5;", true, true, false),
            new Detection("N;", true, true, false),
            new Detection("s:3:\"abc\";", true, true, true),
            new Detection("s:10:\"abc\";", true, true, true),
            new Detection("E:9:\"Foo:Hello\";", true, true, false),
            new Detection("a:1:{i:0;i:1;}\n", true, true, false),
            new Detection("\ta:0:{}\r\n", true, true, false),
            new Detection("\0a:0:{}", true, true, false),
            new Detection("\u2003a:0:{}", false, false, false),
            new Detection("i:5;xyz", false, true, false),
            new Detection("a:0:{};", true, true, false),
            new Detection("C:3:\"Foo\":5:{hello}", false, false, false),
            new Detection("d:INF;", false, false, false),
            new Detection("d:-INF;", false, false, false),
            new Detection("d:NAN;", false, false, false),
            new Detection("d:1e5;", false, false, false),
            new Detection("d:1.5e3;", false, false, false),
            new Detection("d:+1;", true, true, false),
            new Detection("s:3:\"abc\"}", true, true, false),
            new Detection("a:x}", false, false, false),
            new Detection("O:x}", false, false, false),
            new Detection("s:x\";", false, false, true),
            new Detection("E:x\";", false, false, false),
            new Detection("b:2;", true, true, false),
            new Detection("S:3:\"\\61bc\";", false, false, false),
            new Detection("b:0;x", false, true, false),
            new Detection(" b:0;", true, true, false),
            new Detection("hello", false, false, false),
            new Detection("i:5", false, false, false),
            new Detection("a:2147483647:{", false, false, false),
            new Detection("i:5;}", false, true, false),
            new Detection("abc;def}", false, false, false),
            new Detection("", false, false, false),
        };
    }

    @ParameterizedTest
    @MethodSource("detections")
    void detectionMatchesWordPress(Detection d) { // #5 to #9
        assertThat(WordPressSerialization.isSerialized(d.input())).as("strict").isEqualTo(d.strict());
        assertThat(WordPressSerialization.isSerialized(d.input(), true))
                .as("strict")
                .isEqualTo(d.strict());
        assertThat(WordPressSerialization.isSerialized(d.input(), false))
                .as("loose")
                .isEqualTo(d.loose());
        assertThat(WordPressSerialization.isSerializedString(d.input()))
                .as("string")
                .isEqualTo(d.serializedString());
    }

    @Test
    void nullIsNeverSerialized() {
        assertThat(WordPressSerialization.isSerialized(null)).isFalse();
        assertThat(WordPressSerialization.isSerialized(null, false)).isFalse();
        assertThat(WordPressSerialization.isSerializedString(null)).isFalse();
    }

    @Test
    void maybeSerializeSerializesTextThatLooksSerializedAgain() { // #1
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("a:1:{i:0;i:1;}"))))
                .isEqualTo("s:14:\"a:1:{i:0;i:1;}\";");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("i:5;"))))
                .isEqualTo("s:4:\"i:5;\";");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("N;"))))
                .isEqualTo("s:2:\"N;\";");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("i:5;xyz"))))
                .isEqualTo("s:7:\"i:5;xyz\";");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("hello"))))
                .isEqualTo("hello");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("d:INF;"))))
                .isEqualTo("d:INF;");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpString.of("C:3:\"Foo\":5:{hello}"))))
                .isEqualTo("C:3:\"Foo\":5:{hello}");
    }

    @Test
    void maybeSerializeWritesScalarsAsTheDatabaseText() {
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpInt(5)))).isEqualTo("5");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpBool.TRUE))).isEqualTo("1");
        assertThat(text(WordPressSerialization.maybeSerialize(PhpBool.FALSE))).isEmpty();
        assertThat(text(WordPressSerialization.maybeSerialize(PhpNull.INSTANCE)))
                .isEmpty();
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(0.30000000000000004))))
                .isEqualTo("0.3");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(1e15))))
                .isEqualTo("1.0E+15");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(5e-324))))
                .isEqualTo("4.9406564584125E-324");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(1e7))))
                .isEqualTo("10000000");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(Double.NaN))))
                .isEqualTo("NAN");
        // exact ties keep the trailing zeros of the 14 rounded digits (ruling M6)
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(100000000000005.0))))
                .isEqualTo("1.0000000000000E+14");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpFloat(597007423915705.0))))
                .isEqualTo("5.9700742391570E+14");
    }

    @Test
    void maybeSerializeSerializesArraysAndObjects() {
        PhpArray appended = PhpArray.builder()
                .put(-5, PhpString.of("a"))
                .add(PhpString.of("b"))
                .build();
        assertThat(text(WordPressSerialization.maybeSerialize(appended)))
                .isEqualTo("a:2:{i:-5;s:1:\"a\";i:-4;s:1:\"b\";}");
        PhpObject object = new PhpObject(
                "stdClass", PhpArray.builder().put(5, new PhpInt(1)).build());
        assertThat(text(WordPressSerialization.maybeSerialize(object)))
                .isEqualTo("O:8:\"stdClass\":1:{s:1:\"5\";i:1;}");
        assertThat(text(WordPressSerialization.maybeSerialize(new PhpEnum("Suit", "Hearts"))))
                .isEqualTo("E:11:\"Suit:Hearts\";");
        assertThatThrownBy(
                        () -> WordPressSerialization.maybeSerialize(new PhpReference(1, PhpReference.Kind.REFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maybeUnserializeTrimsLikePhpAndGivesFalseOnFailure() { // #2, #3
        assertThat(WordPressSerialization.maybeUnserialize("a:1:{i:0;i:1;}\n")).isEqualTo(PhpArray.of(new PhpInt(1)));
        assertThat(WordPressSerialization.maybeUnserialize("\ta:0:{}\r\n")).isEqualTo(PhpArray.empty());
        assertThat(WordPressSerialization.maybeUnserialize("\0a:0:{}")).isEqualTo(PhpArray.empty());
        assertThat(WordPressSerialization.maybeUnserialize("\u2003a:0:{}")).isEqualTo(PhpString.of("\u2003a:0:{}"));
        assertThat(WordPressSerialization.maybeUnserialize("s:10:\"abc\";")).isEqualTo(PhpBool.FALSE);
        assertThat(WordPressSerialization.maybeUnserialize("i:5;xyz")).isEqualTo(PhpString.of("i:5;xyz"));
        assertThat(WordPressSerialization.maybeUnserialize("a:0:{};")).isEqualTo(PhpArray.empty());
        assertThat(WordPressSerialization.maybeUnserialize("i:9223372036854775808;"))
                .isEqualTo(new PhpInt(Long.MAX_VALUE));
        assertThat(WordPressSerialization.maybeUnserialize("S:3:\"\\61bc\";"))
                .isEqualTo(PhpString.of("S:3:\"\\61bc\";"));
        assertThat(WordPressSerialization.maybeUnserialize("hello")).isEqualTo(PhpString.of("hello"));
        assertThat(WordPressSerialization.maybeUnserialize(" b:0;")).isEqualTo(PhpBool.FALSE);
    }

    @Test
    void unknownEnumIsKeptAsDataWhereWordPressReturnsFalse() { // #23, a documented difference
        assertThat(WordPressSerialization.maybeUnserialize("E:9:\"Foo:Hello\";"))
                .isEqualTo(new PhpEnum("Foo", "Hello"));
    }

    @Test
    void tryMaybeUnserializeTellsTheCasesApart() {
        assertThat(WordPressSerialization.tryMaybeUnserialize("hello"))
                .isEqualTo(new MaybeUnserializeResult.NotSerialized(PhpString.of("hello")));
        assertThat(WordPressSerialization.tryMaybeUnserialize("b:0;"))
                .isEqualTo(new MaybeUnserializeResult.Parsed(PhpBool.FALSE));
        assertThat(WordPressSerialization.tryMaybeUnserialize("s:10:\"abc\";".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        MaybeUnserializeResult.Failed.class,
                        f -> assertThat(f.error().offset()).isEqualTo(2));
    }

    @Test
    void stringOverloadUsesUtf8Bytes() { // Review Focus 1
        // "é" is two UTF-8 bytes, so a declared length of 1 is wrong, as it is for PHP reading UTF-8 bytes
        assertThat(WordPressSerialization.maybeUnserialize("s:1:\"é\";")).isEqualTo(PhpBool.FALSE);
        assertThat(WordPressSerialization.maybeUnserialize("s:2:\"é\";")).isEqualTo(PhpString.of("é"));
        byte[] latin1 = "s:1:\"é\";".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(((PhpString) WordPressSerialization.maybeUnserialize(latin1)).bytes())
                .containsExactly((byte) 0xE9);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
