package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * bcrypt (Provos and Mazières' EksBlowfish in OpenBSD's {@code $2b$} format) with the rules of PHP's
 * {@code password_verify}: the password ends at its first NUL byte and after 72 bytes, {@code $2b$} and {@code $2y$}
 * are one algorithm, and {@code $2a$} carries PHP's countermeasure for some keys with 8-bit bytes. {@code $2x$} is
 * not supported, and stored costs above {@link #MAX_COST} are refused without computing.
 */
final class Bcrypt {

    static final int HASH_LENGTH = 60;
    static final int MIN_COST = 4;
    /** PHP accepts costs up to 31; this library refuses stored costs above 16. */
    static final int MAX_COST = 16;

    private static final int FORMAT_MAX_COST = 31;
    private static final int MAX_KEY_BYTES = 72;
    private static final int KEY_WORDS = 18;
    private static final int SALT_BYTES = 16;
    private static final int SALT_CHARS = 22;
    private static final int DIGEST_BYTES = 23;
    private static final int DIGEST_CHARS = 31;
    private static final String ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final byte[] DECODE = decodeTable();
    /** The 24-byte text bcrypt encrypts 64 times, as six big-endian words. */
    private static final int[] MAGIC = bigEndianWords("OrpheanBeholderScryDoubt".getBytes(US_ASCII));

    private Bcrypt() {}

    /** Whether {@code stored} is a supported bcrypt hash of {@code password}; never throws for malformed input. */
    static boolean verify(byte[] password, byte[] stored) {
        if (stored.length != HASH_LENGTH
                || stored[0] != '$'
                || stored[1] != '2'
                || stored[3] != '$'
                || stored[6] != '$') {
            return false;
        }
        char minor = (char) stored[2];
        if (minor != 'a' && minor != 'b' && minor != 'y') {
            return false;
        }
        int cost = twoDigits(stored[4], stored[5]);
        if (cost < MIN_COST || cost > FORMAT_MAX_COST) {
            return false;
        }
        byte[] salt = decodeSalt(stored, 7);
        if (salt == null || !inAlphabet(stored, 7 + SALT_CHARS, DIGEST_CHARS) || cost > MAX_COST) {
            return false;
        }
        byte[] computed = hash(password, minor, cost, salt).getBytes(US_ASCII);
        return MessageDigest.isEqual(computed, stored);
    }

    /** The 60-character bcrypt string of {@code password}; {@code minor} is {@code a}, {@code b} or {@code y}. */
    static String hash(byte[] password, char minor, int cost, byte[] salt) {
        if (minor != 'a' && minor != 'b' && minor != 'y') {
            throw new IllegalArgumentException("unsupported bcrypt variant $2" + minor + "$");
        }
        if (cost < MIN_COST || cost > FORMAT_MAX_COST) {
            throw new IllegalArgumentException("cost must be 4 to 31: " + cost);
        }
        if (salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt must be 16 bytes: " + salt.length);
        }
        byte[] key = keyBytes(password);
        int[] keyWords = keyWords(key, false);
        int[] firstKeyWords = keyWords;
        if (minor == 'a' && countermeasureApplies(key, keyWords)) {
            firstKeyWords = keyWords.clone();
            firstKeyWords[0] ^= 0x10000;
        }
        int[] saltWords = bigEndianWords(salt);
        int[] saltAsKey = new int[KEY_WORDS];
        for (int i = 0; i < KEY_WORDS; i++) {
            saltAsKey[i] = saltWords[i % saltWords.length];
        }

        Blowfish state = new Blowfish();
        state.expand(firstKeyWords, saltWords);
        for (long rounds = 1L << cost; rounds > 0; rounds--) {
            state.expand(keyWords, null);
            state.expand(saltAsKey, null);
        }
        int[] text = MAGIC.clone();
        for (int i = 0; i < 64; i++) {
            for (int block = 0; block < text.length; block += 2) {
                state.encrypt(text, block);
            }
        }
        byte[] digest = new byte[4 * text.length];
        for (int i = 0; i < text.length; i++) {
            digest[4 * i] = (byte) (text[i] >>> 24);
            digest[4 * i + 1] = (byte) (text[i] >>> 16);
            digest[4 * i + 2] = (byte) (text[i] >>> 8);
            digest[4 * i + 3] = (byte) text[i];
        }

        StringBuilder out = new StringBuilder(HASH_LENGTH)
                .append("$2")
                .append(minor)
                .append('$')
                .append((char) ('0' + cost / 10))
                .append((char) ('0' + cost % 10))
                .append('$');
        encode(salt, SALT_BYTES, out);
        encode(digest, DIGEST_BYTES, out);
        return out.toString();
    }

    /** The 16 salt bytes that 22 bcrypt characters encode; {@code null} if a character is outside the alphabet. */
    static byte[] decodeSalt(String saltChars) {
        if (saltChars.length() != SALT_CHARS) {
            throw new IllegalArgumentException("a bcrypt salt has 22 characters: " + saltChars);
        }
        return decodeSalt(saltChars.getBytes(US_ASCII), 0);
    }

    /** PHP passes the password to bcrypt as a C string: the bytes before the first NUL, at most 72 of them. */
    private static byte[] keyBytes(byte[] password) {
        int end = 0;
        while (end < password.length && end < MAX_KEY_BYTES && password[end] != 0) {
            end++;
        }
        return Arrays.copyOf(password, end);
    }

    /**
     * Eighteen words from the key bytes followed by one NUL byte, repeated as often as needed, read four bytes per
     * word, first byte highest. With {@code signExtend} every byte is first widened as a signed value, which
     * reproduces the historical 8-bit bug that the {@code $2x$} id stands for.
     */
    private static int[] keyWords(byte[] key, boolean signExtend) {
        int[] words = new int[KEY_WORDS];
        int cycle = key.length + 1;
        int position = 0;
        for (int i = 0; i < KEY_WORDS; i++) {
            int word = 0;
            for (int j = 0; j < 4; j++) {
                int k = position++ % cycle;
                byte b = k < key.length ? key[k] : 0;
                word = (word << 8) | (signExtend ? b : b & 0xff);
            }
            words[i] = word;
        }
        return words;
    }

    /**
     * PHP's {@code $2a$} countermeasure applies when the key stream has a byte with the high bit set in the second,
     * third or fourth position of some word, and yet sign-extending every byte would build the very same words.
     */
    private static boolean countermeasureApplies(byte[] key, int[] keyWords) {
        boolean highBitInsideWord = false;
        int cycle = key.length + 1;
        for (int position = 0; position < 4 * KEY_WORDS && !highBitInsideWord; position++) {
            int k = position % cycle;
            highBitInsideWord = position % 4 != 0 && k < key.length && key[k] < 0;
        }
        return highBitInsideWord && Arrays.equals(keyWords, keyWords(key, true));
    }

    private static byte[] decodeSalt(byte[] text, int at) {
        byte[] salt = new byte[SALT_BYTES];
        int buffer = 0;
        int bits = 0;
        int filled = 0;
        for (int i = at; i < at + SALT_CHARS; i++) {
            int value = text[i] >= 0 ? DECODE[text[i]] : -1;
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (filled < SALT_BYTES) {
                    salt[filled++] = (byte) (buffer >>> bits);
                }
                buffer &= (1 << bits) - 1;
            }
        }
        return salt;
    }

    private static boolean inAlphabet(byte[] text, int at, int count) {
        for (int i = at; i < at + count; i++) {
            if (text[i] < 0 || DECODE[text[i]] < 0) {
                return false;
            }
        }
        return true;
    }

    /** bcrypt's Base64: its own alphabet, most significant bits first, no padding (n bytes give ⌈8n/6⌉ chars). */
    private static void encode(byte[] data, int length, StringBuilder out) {
        for (int i = 0; i < length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < length ? data[i + 2] & 0xff : 0;
            out.append(ALPHABET.charAt(b0 >>> 2));
            out.append(ALPHABET.charAt(((b0 & 0x03) << 4) | (b1 >>> 4)));
            if (i + 1 < length) {
                out.append(ALPHABET.charAt(((b1 & 0x0f) << 2) | (b2 >>> 6)));
            }
            if (i + 2 < length) {
                out.append(ALPHABET.charAt(b2 & 0x3f));
            }
        }
    }

    private static int twoDigits(byte tens, byte units) {
        if (tens < '0' || tens > '9' || units < '0' || units > '9') {
            return -1;
        }
        return (tens - '0') * 10 + (units - '0');
    }

    private static int[] bigEndianWords(byte[] bytes) {
        int[] words = new int[bytes.length / 4];
        for (int i = 0; i < words.length; i++) {
            words[i] = (bytes[4 * i] & 0xff) << 24
                    | (bytes[4 * i + 1] & 0xff) << 16
                    | (bytes[4 * i + 2] & 0xff) << 8
                    | (bytes[4 * i + 3] & 0xff);
        }
        return words;
    }

    private static byte[] decodeTable() {
        byte[] table = new byte[128];
        Arrays.fill(table, (byte) -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            table[ALPHABET.charAt(i)] = (byte) i;
        }
        return table;
    }
}
