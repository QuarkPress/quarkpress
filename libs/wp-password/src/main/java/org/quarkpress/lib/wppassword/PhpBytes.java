package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Byte-level helpers that look at strings the way PHP does: as bytes, measured and compared byte by byte. */
final class PhpBytes {

    private PhpBytes() {}

    /** The UTF-8 bytes of {@code text}; an unpaired surrogate becomes {@code ?}, as Java's encoder writes it. */
    static byte[] utf8(String text) {
        return text.getBytes(UTF_8);
    }

    /** Whether {@code bytes} holds the ASCII text {@code prefix} at {@code offset}. */
    static boolean startsWith(byte[] bytes, int offset, String prefix) {
        if (offset < 0 || bytes.length - offset < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[offset + i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code bytes} starts with the ASCII text {@code prefix}. */
    static boolean startsWith(byte[] bytes, String prefix) {
        return startsWith(bytes, 0, prefix);
    }

    /** Exactly 32 lowercase hexadecimal digits, the form PHP's md5() returns. */
    static boolean isLowercaseMd5Hex(byte[] bytes) {
        if (bytes.length != 32) {
            return false;
        }
        for (byte b : bytes) {
            boolean digit = b >= '0' && b <= '9';
            boolean letter = b >= 'a' && b <= 'f';
            if (!digit && !letter) {
                return false;
            }
        }
        return true;
    }
}
