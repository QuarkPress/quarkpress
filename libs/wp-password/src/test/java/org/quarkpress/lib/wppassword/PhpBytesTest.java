package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PhpBytesTest {

    @Test
    void md5HexIsLowercase() {
        assertThat(new String(PhpBytes.md5Hex(PhpBytes.utf8("password")), US_ASCII))
                .isEqualTo("5f4dcc3b5aa765d61d8327deb882cf99");
        assertThat(new String(PhpBytes.md5Hex(new byte[0]), US_ASCII)).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    void addSlashesEscapesQuotesBackslashAndNul() {
        byte[] in = {'a', 0, 'b', '\'', '"', '\\'};
        assertThat(HexFormat.of().formatHex(PhpBytes.addSlashes(in))).isEqualTo("615c30625c275c225c5c");
        assertThat(PhpBytes.addSlashes(PhpBytes.utf8("plain é"))).isEqualTo(PhpBytes.utf8("plain é"));
    }

    @Test
    void lowercaseMd5HexIsExactly32Digits() {
        assertThat(PhpBytes.isLowercaseMd5Hex(PhpBytes.utf8("5f4dcc3b5aa765d61d8327deb882cf99")))
                .isTrue();
        assertThat(PhpBytes.isLowercaseMd5Hex(PhpBytes.utf8("5F4DCC3B5AA765D61D8327DEB882CF99")))
                .isFalse();
        assertThat(PhpBytes.isLowercaseMd5Hex(PhpBytes.utf8("5f4dcc3b5aa765d61d8327deb882cf9")))
                .isFalse();
        assertThat(PhpBytes.isLowercaseMd5Hex(PhpBytes.utf8("5f4dcc3b5aa765d61d8327deb882cfg9")))
                .isFalse();
    }

    @Test
    void startsWithChecksBoundsAndOffset() {
        byte[] hash = PhpBytes.utf8("$wp$2y$");
        assertThat(PhpBytes.startsWith(hash, "$wp")).isTrue();
        assertThat(PhpBytes.startsWith(hash, 3, "$2y$")).isTrue();
        assertThat(PhpBytes.startsWith(hash, 4, "$2y$")).isFalse();
        assertThat(PhpBytes.startsWith(hash, "$wp$2y$0")).isFalse();
        assertThat(PhpBytes.startsWith(new byte[0], "")).isTrue();
    }

    @Test
    void utf8WritesQuestionMarkForUnpairedSurrogates() {
        assertThat(PhpBytes.utf8("\uD800")).isEqualTo(new byte[] {'?'});
    }
}
