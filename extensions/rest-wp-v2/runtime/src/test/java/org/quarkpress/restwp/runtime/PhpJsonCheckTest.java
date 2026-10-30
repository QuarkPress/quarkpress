package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Expected codes are PHP 8.4's {@code json_last_error()} after {@code json_decode($text, true)}. */
class PhpJsonCheckTest {

    @Test
    void validTextsPass() {
        for (String text : new String[] {
            "null",
            "1",
            "-0",
            "1.5E+3",
            "\"\"",
            "[]",
            "{}",
            " [1, {\"a\": [true, false, null]}] ",
            "\"\\ud83d\\ude00\"",
            "{\"\\u0000a\":1}",
            "123456789012345678901234567890",
            "[" + "[".repeat(509) + "]".repeat(509) + "]"
        }) {
            assertThat(PhpJsonCheck.check(text.getBytes(StandardCharsets.UTF_8)))
                    .as(text)
                    .isEmpty();
        }
    }

    @Test
    void errorsAreReportedAsPhpReportsThem() {
        assertCode("[".repeat(511) + "]".repeat(511), 0);
        assertCode("[".repeat(512) + "]".repeat(512), 1);
        assertCode("{\"a\":".repeat(512) + "1" + "}".repeat(512), 1);
        assertCode("[".repeat(600), 1);
        assertCode("[1," + "[".repeat(511) + "]", 1);
        assertCode("[}", 2);
        assertCode("{\"a\":1]", 2);
        assertCode("[[1]}", 2);
        assertCode("[\"a\u0001\"]", 3);
        assertCode("[\"a\tb\"]", 3);
        assertCode("[1,\u0001]", 3);
        assertCode("[\"abc", 3);
        assertCode("[1]\u0000", 3);
        assertCode(" ", 4);
        assertCode("[1] x", 4);
        assertCode("[1] [2]", 4);
        assertCode("TRUE", 4);
        assertCode("[1,]", 4);
        assertCode("{\"a\":1,}", 4);
        assertCode("[1,}", 4);
        assertCode("{1:1}", 4);
        assertCode("01", 4);
        assertCode("1.", 4);
        assertCode("-", 4);
        assertCode("\"\\x\"", 4);
        assertCode("\"\\u12g4\"", 4);
        assertCode("[x\u0001]", 4);
        assertCode("]", 4);
        assertCode("\"\\ude00\"", 10);
        assertCode("\"\\ud83dx\"", 10);
        assertCode("\"\\ud83d\\ud83d\"", 10);
        assertCode("\"\\ud83d", 10);
    }

    @Test
    void bytesThatAreNotUtf8() {
        assertBytes(new byte[] {'[', '"', 'a', (byte) 0xFF, '"', ']'}, 5);
        assertBytes(new byte[] {'[', (byte) 0xFF, ']'}, 5);
        assertBytes(new byte[] {'1', (byte) 0xFF}, 5);
        assertBytes(new byte[] {'[', '"', (byte) 0xC0, (byte) 0xAF, '"', ']'}, 5);
        assertBytes(new byte[] {'[', '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"', ']'}, 5);
        assertBytes(new byte[] {'[', '"', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, '"', ']'}, 5);
        // valid UTF-8 outside a string, a byte order mark among it, is a syntax error
        assertBytes(new byte[] {'[', (byte) 0xC3, (byte) 0xA9, ']'}, 4);
        assertBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '[', '1', ']'}, 4);
        // the first problem in the text wins
        assertBytes(new byte[] {'[', '"', 1, '"', ',', '"', (byte) 0xFF, '"', ']'}, 3);
        assertBytes(new byte[] {'[', '"', (byte) 0xFF, '"', ',', '"', 1, '"', ']'}, 5);
        assertBytes(new byte[] {'[', 'x', (byte) 0xFF, ']'}, 4);
    }

    private static void assertCode(String text, int code) {
        assertBytes(text.getBytes(StandardCharsets.UTF_8), code);
    }

    private static void assertBytes(byte[] text, int code) {
        assertThat(PhpJsonCheck.check(text).map(RestJsonFailure::code).orElse(0))
                .as(new String(text, StandardCharsets.ISO_8859_1))
                .isEqualTo(code);
    }
}
