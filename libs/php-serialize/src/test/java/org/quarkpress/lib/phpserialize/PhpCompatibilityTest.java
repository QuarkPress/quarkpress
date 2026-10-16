package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhpCompatibilityTest {

    private static PhpValue lenient(String in) {
        return PhpSerialize.parse(in, ParseOptions.PHP_COMPATIBLE);
    }

    @Test
    void plusSignIsAcceptedOnIntegersKeysAndFloats() { // #20
        assertThat(PhpSerialize.parse("i:+5;")).isEqualTo(new PhpInt(5));
        assertThat(((PhpArray) PhpSerialize.parse("a:1:{i:+3;i:1;}")).get(3)).isEqualTo(new PhpInt(1));
        PhpFloat one = (PhpFloat) PhpSerialize.parse("d:+1;");
        assertThat(one.value()).isEqualTo(1.0);
        assertThat(one.raw()).isEqualTo("+1");
        assertThat(((PhpFloat) PhpSerialize.parse("d:+.5;")).value()).isEqualTo(0.5);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "s:+1:\"a\";",
                "a:+1:{i:0;i:1;}",
                "a:2:{i:0;i:1;i:1;R:+2;}",
                "b:+1;",
                "d:+-1;",
                "d:+INF;",
                "i:+-5;"
            })
    void plusSignIsRejectedWherePhpRejectsIt(String in) { // #20
        assertThatThrownBy(() -> lenient(in)).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void phpCompatibleAcceptsTrailingData() { // #4
        assertThatThrownBy(() -> PhpSerialize.parse("i:5;xyz")).isInstanceOf(PhpSerializeException.class);
        assertThat(lenient("i:5;xyz")).isEqualTo(new PhpInt(5));
        assertThat(lenient("a:0:{};")).isEqualTo(PhpArray.empty());
        assertThat(lenient("a:0:{}a:0:{}")).isEqualTo(PhpArray.empty());
        assertThat(lenient("O:8:\"stdClass\":0:{};")).isEqualTo(new PhpObject("stdClass", PhpArray.empty()));
        assertThat(lenient("b:0;x")).isEqualTo(PhpBool.FALSE);
    }

    @Test
    void phpCompatibleClampsIntegers() { // #19
        assertThat(lenient("i:9223372036854775808;")).isEqualTo(new PhpInt(Long.MAX_VALUE));
        assertThat(lenient("i:-9223372036854775809;")).isEqualTo(new PhpInt(Long.MIN_VALUE));
        assertThat(lenient("i:99999999999999999999;")).isEqualTo(new PhpInt(Long.MAX_VALUE));
        assertThat(((PhpArray) lenient("a:1:{i:99999999999999999999;i:1;}")).get(Long.MAX_VALUE))
                .isEqualTo(new PhpInt(1));
        assertThat(PhpSerialize.serializeToString(lenient("i:9223372036854775808;")))
                .isEqualTo("i:9223372036854775807;");
        assertThatThrownBy(() -> PhpSerialize.parse("i:9223372036854775808;"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void phpCompatibleReadsLegacyEscapedStrings() { // #21
        assertThat(lenient("S:3:\"\\61bc\";")).isEqualTo(PhpString.of("abc"));
        assertThat(lenient("S:1:\"\\4a\";")).isEqualTo(PhpString.of("J"));
        assertThat(lenient("S:3:\"abc\";")).isEqualTo(PhpString.of("abc"));
        assertThat(PhpSerialize.serializeToString(lenient("S:3:\"\\61bc\";"))).isEqualTo("s:3:\"abc\";");
        assertThatThrownBy(() -> lenient("S:1:\"\\zz\";")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("S:3:\"abc\";"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("'S'");
    }

    @Test
    void phpCompatibleReadsLegacyEscapedKeys() { // #21, PHP: a:1:{S:1:"\61";i:1;} is ["a" => 1]
        PhpArray array = (PhpArray) lenient("a:1:{S:1:\"\\61\";i:1;}");
        assertThat(array.get("a")).isEqualTo(new PhpInt(1));
        assertThat(PhpSerialize.serializeToString(array)).isEqualTo("a:1:{s:1:\"a\";i:1;}");
        assertThatThrownBy(() -> PhpSerialize.parse("a:1:{S:1:\"\\61\";i:1;}"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("array key");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "O:3:\"a b\":0:{}",
                "O:4:\"\\Abc\":0:{}",
                "O:0:\"\":0:{}",
                "O:3:\"a-b\":0:{}",
                "O:2:\"\\\\\":0:{}",
                "C:3:\"a b\":0:{}",
                "E:7:\"a b:Foo\";"
            })
    void classNamesMustBePhpIdentifiers(String in) { // #22
        assertThatThrownBy(() -> PhpSerialize.parse(in))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("class name");
    }

    @Test
    void classNamesThatPhpAcceptsParse() { // #22
        assertThat(((PhpObject) PhpSerialize.parse("O:3:\"1ab\":0:{}")).className())
                .isEqualTo("1ab");
        assertThat(((PhpObject) PhpSerialize.parse("O:4:\"A\\Bc\":0:{}")).className())
                .isEqualTo("A\\Bc");
        assertThat(((PhpObject) PhpSerialize.parse("O:4:\"Abc\\\":0:{}")).className())
                .isEqualTo("Abc\\");
        assertThat(((PhpObject) PhpSerialize.parse("O:2:\"é\":0:{}")).className())
                .isEqualTo("é");
        assertThat(PhpSerialize.parse("E:15:\"App\\Suit:Hearts\";")).isEqualTo(new PhpEnum("App\\Suit", "Hearts"));
    }

    @Test
    void nonUtf8ClassNameDecodesWithReplacement() { // Review Focus 3: documented difference, skip-listed as #22
        byte[] in = "O:1:\"\u0080\":0:{}".getBytes(StandardCharsets.ISO_8859_1);
        PhpObject object = (PhpObject) PhpSerialize.parse(in);
        assertThat(object.className()).isEqualTo("\uFFFD");
        assertThat(PhpSerialize.serialize(object)).isNotEqualTo(in);
    }
}
