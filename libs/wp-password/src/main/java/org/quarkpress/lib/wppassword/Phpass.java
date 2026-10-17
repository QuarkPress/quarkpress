package org.quarkpress.lib.wppassword;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * phpass "portable" hashes ({@code $P$} from WordPress 2.5 to 6.7, {@code $H$} from phpBB): an 8-byte salt and
 * 2^n chained MD5 rounds over the password. Which ids a caller accepts is the caller's decision. The round count is
 * bounded: a stored hash asking for more than 2^20 rounds is refused instead of computed.
 */
final class Phpass {

    /** The 64 characters the hash text is written in, in value order. */
    static final String ALPHABET = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    static final int LENGTH = 34;

    private static final int SETTING_BYTES = 12;
    private static final int SALT_BYTES = 8;
    private static final int MIN_LOG2_ROUNDS = 7;
    private static final int MAX_LOG2_ROUNDS = 20;

    private Phpass() {}

    /** Whether the 34-byte {@code stored} hash is the phpass hash of {@code password}, compared in constant time. */
    static boolean verify(byte[] password, byte[] stored) {
        if (stored.length != LENGTH) {
            return false;
        }
        byte[] computed = compute(password, stored);
        return computed != null && MessageDigest.isEqual(computed, stored);
    }

    /**
     * The 34-byte hash for the first 12 bytes of {@code setting} ({@code $P$} or {@code $H$}, a count character,
     * an 8-byte salt), or {@code null} when those bytes do not form a usable setting.
     */
    static byte[] compute(byte[] password, byte[] setting) {
        if (setting.length < SETTING_BYTES
                || setting[0] != '$'
                || (setting[1] != 'P' && setting[1] != 'H')
                || setting[2] != '$') {
            return null;
        }
        int log2Rounds = ALPHABET.indexOf(setting[3] & 0xff);
        if (log2Rounds < MIN_LOG2_ROUNDS || log2Rounds > MAX_LOG2_ROUNDS) {
            return null;
        }
        MessageDigest md5 = PhpBytes.md5();
        md5.update(setting, 4, SALT_BYTES);
        md5.update(password);
        byte[] digest = md5.digest();
        for (long round = 1L << log2Rounds; round > 0; round--) {
            md5.update(digest);
            md5.update(password);
            digest = md5.digest();
        }
        byte[] out = Arrays.copyOf(setting, LENGTH);
        encode(digest, out, SETTING_BYTES);
        return out;
    }

    /**
     * The hash text's Base64 variant: each group of up to three bytes is read as one little-endian integer and
     * written six bits at a time, lowest bits first; a final group of n bytes gives n + 1 characters, a full group
     * four.
     */
    private static void encode(byte[] data, byte[] out, int at) {
        int next = at;
        for (int i = 0; i < data.length; i += 3) {
            int count = Math.min(3, data.length - i);
            int group = 0;
            for (int k = 0; k < count; k++) {
                group |= (data[i + k] & 0xff) << (8 * k);
            }
            int chars = count == 3 ? 4 : count + 1;
            for (int c = 0; c < chars; c++) {
                out[next++] = (byte) ALPHABET.charAt((group >>> (6 * c)) & 0x3f);
            }
        }
    }
}
