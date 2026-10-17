package org.quarkpress.lib.wpshortcode;

import java.nio.charset.StandardCharsets;

/**
 * C-style backslash escapes as PHP's stripcslashes reads them: {@code \n \t \r \a \v \b \f \\}, {@code \x} with
 * one or two hex digits, one to three octal digits (taken modulo 256), and a backslash before anything else is
 * dropped ({@code \e} is just {@code e}); a lone trailing backslash stays. PHP works on bytes, so this works on
 * the UTF-8 bytes of the value and decodes the result as UTF-8, replacing invalid sequences with U+FFFD.
 */
final class BackslashEscapes {

    private BackslashEscapes() {}

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        byte[] in = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[in.length];
        int o = 0;
        for (int i = 0; i < in.length; i++) {
            int b = in[i] & 0xFF;
            if (b != '\\' || i + 1 >= in.length) {
                out[o++] = (byte) b;
                continue;
            }
            int c = in[++i] & 0xFF;
            switch (c) {
                case 'n' -> out[o++] = '\n';
                case 't' -> out[o++] = '\t';
                case 'r' -> out[o++] = '\r';
                case 'a' -> out[o++] = 0x07;
                case 'v' -> out[o++] = 0x0B;
                case 'b' -> out[o++] = 0x08;
                case 'f' -> out[o++] = 0x0C;
                case '\\' -> out[o++] = '\\';
                case 'x' -> {
                    if (i + 1 < in.length && hexValue(in[i + 1]) >= 0) {
                        int v = hexValue(in[++i]);
                        if (i + 1 < in.length && hexValue(in[i + 1]) >= 0) {
                            v = v * 16 + hexValue(in[++i]);
                        }
                        out[o++] = (byte) v;
                    } else {
                        out[o++] = 'x';
                    }
                }
                default -> {
                    if (c >= '0' && c <= '7') {
                        int v = c - '0';
                        for (int digits = 1; digits < 3 && i + 1 < in.length && isOctal(in[i + 1]); digits++) {
                            v = v * 8 + (in[++i] - '0');
                        }
                        out[o++] = (byte) v;
                    } else {
                        out[o++] = (byte) c;
                    }
                }
            }
        }
        return new String(out, 0, o, StandardCharsets.UTF_8);
    }

    private static boolean isOctal(byte b) {
        return b >= '0' && b <= '7';
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}
