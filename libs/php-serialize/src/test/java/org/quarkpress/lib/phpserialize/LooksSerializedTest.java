package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LooksSerializedTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "N;",
                "b:1;",
                "i:42;",
                "i:-1;",
                "d:0.5;",
                "d:INF;",
                "d:NAN;",
                "s:3:\"abc\";",
                "a:0:{}",
                "O:8:\"stdClass\":0:{}",
                "C:3:\"Foo\":0:{}",
                "E:3:\"A:B\";",
                " a:0:{} ",
                "\na:0:{}\n"
            })
    void detectsSerialized(String s) {
        assertThat(PhpSerialize.looksSerialized(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "hello",
                "N",
                "N:",
                "a:0:{",
                "s:3:\"abc\"",
                "s:3:abc;",
                "i:x;",
                "{\"json\":1}",
                "12345",
                "b:1",
                "a;b;c;",
                "x:1;"
            })
    void rejectsPlainStrings(String s) {
        assertThat(PhpSerialize.looksSerialized(s)).isFalse();
    }

    @Test
    void nullIsNotSerialized() {
        assertThat(PhpSerialize.looksSerialized(null)).isFalse();
    }
}
