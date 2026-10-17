package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Byte-level helpers that look at strings the way PHP does: as bytes, measured and compared byte by byte. */
final class PhpBytes {

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(US_ASCII);

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

    /** A fresh MD5 digest. */
    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides MD5", e);
        }
    }

    /** The MD5 of {@code data} as 32 lowercase hexadecimal ASCII bytes. */
    static byte[] md5Hex(byte[] data) {
        byte[] digest = md5().digest(data);
        byte[] hex = new byte[2 * digest.length];
        for (int i = 0; i < digest.length; i++) {
            hex[2 * i] = HEX_DIGITS[(digest[i] >>> 4) & 0x0f];
            hex[2 * i + 1] = HEX_DIGITS[digest[i] & 0x0f];
        }
        return hex;
    }

    /**
     * PHP's addslashes(): a backslash before every {@code '}, {@code "} and {@code \}, and a NUL byte written as
     * backslash and {@code 0}. Returns {@code data} itself when nothing needs escaping.
     */
    static byte[] addSlashes(byte[] data) {
        int extra = 0;
        for (byte b : data) {
            if (b == '\'' || b == '"' || b == '\\' || b == 0) {
                extra++;
            }
        }
        if (extra == 0) {
            return data;
        }
        byte[] out = new byte[data.length + extra];
        int j = 0;
        for (byte b : data) {
            if (b == 0) {
                out[j++] = '\\';
                out[j++] = '0';
            } else {
                if (b == '\'' || b == '"' || b == '\\') {
                    out[j++] = '\\';
                }
                out[j++] = b;
            }
        }
        return out;
    }
}
