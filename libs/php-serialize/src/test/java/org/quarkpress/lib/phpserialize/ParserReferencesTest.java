package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParserReferencesTest {

    /** Every input below is accepted by PHP 8.4's unserialize(). */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "a:2:{i:0;a:1:{i:0;i:1;}i:1;R:2;}",
                "a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}",
                "a:2:{i:0;i:5;i:1;R:2;}",
                "a:1:{i:0;R:1;}",
                "O:8:\"stdClass\":1:{s:1:\"a\";r:1;}",
                "O:8:\"stdClass\":1:{s:1:\"a\";R:1;}",
                "a:3:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;i:2;r:3;}",
                "a:3:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;i:2;R:3;}",
                "a:2:{i:0;C:3:\"Foo\":0:{}i:1;r:2;}",
                "a:2:{s:1:\"x\";i:1;s:1:\"y\";R:2;}",
                "a:1:{i:0;O:8:\"stdClass\":1:{s:1:\"a\";r:2;}}"
            })
    void acceptsReferencesThatPhpAccepts(String in) {
        assertThat(PhpSerialize.serializeToString(PhpSerialize.parse(in))).isEqualTo(in);
    }

    /** Every input below is rejected by PHP 8.4's unserialize() (#15). */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "a:1:{i:0;R:5;}",
                "a:1:{i:0;r:1;}",
                "a:2:{i:0;i:1;i:1;r:2;}",
                "R:1;",
                "r:1;",
                "a:1:{i:0;R:0;}",
                "a:3:{i:0;i:1;i:1;R:2;i:2;R:3;}",
                "a:2:{i:0;a:0:{}i:1;r:2;}",
                "a:1:{i:0;r:2;}",
                "a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:3;}",
                "a:2:{i:0;O:8:\"stdClass\":0:{}i:1;R:3;}",
                "a:3:{i:0;O:8:\"stdClass\":1:{s:1:\"p\";i:7;}i:1;R:3;i:2;R:4;}",
                "a:1:{i:0;a:1:{i:0;r:2;}}"
            })
    void rejectsReferencesThatPhpRejects(String in) {
        assertThatThrownBy(() -> PhpSerialize.parse(in, ParseOptions.PHP_COMPATIBLE))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("refer");
    }

    @Test
    void parserReportsDuplicateKeys() {
        PhpParser duplicate = new PhpParser("a:2:{i:0;i:1;i:0;i:2;}".getBytes(UTF_8), ParseOptions.DEFAULTS);
        duplicate.parseDocument();
        assertThat(duplicate.sawDuplicateKey()).isTrue();
        PhpParser plain = new PhpParser("a:2:{i:0;i:1;i:1;i:2;}".getBytes(UTF_8), ParseOptions.DEFAULTS);
        plain.parseDocument();
        assertThat(plain.sawDuplicateKey()).isFalse();
    }
}
