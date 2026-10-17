package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.quarkpress.testing.gen.Gens.weighted;

import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/** Generators shared by the property tests and the parity test (testing/test-gen; seeded, deterministic). */
final class PasswordGens {

    static final String BCRYPT_ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static final String BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    static final String HEX_ALPHABET = "0123456789abcdef";

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private PasswordGens() {}

    /**
     * Passwords as WordPress may receive them: text, arbitrary bytes (NUL and invalid UTF-8 included), the characters
     * that trim() and addslashes() touch, and lengths around the 4096-byte limit.
     */
    static Gen<byte[]> passwords() {
        return Gens.<byte[]>frequency(
                weighted(4, Gens.strings(Gens.unicodeChars(), 0, 40).map(PhpBytes::utf8)),
                weighted(3, Gens.bytes(0, 80)),
                weighted(2, Gens.strings(" \t\n\r\13\0'\"\\aZ9é", 0, 16).map(PhpBytes::utf8)),
                weighted(1, Gens.ints(4090, 4100).map(n -> "x".repeat(n).getBytes(US_ASCII))));
    }

    /** Look-alikes of stored hashes: real prefixes followed by text from a mixed alphabet; almost never valid. */
    static Gen<String> garbageHashes() {
        Gen<String> prefixes = Gens.elements(
                "",
                "$wp",
                "$wp$2y$05$",
                "$P$B",
                "$H$9",
                "$2y$05$",
                "$2a$04$",
                "$2x$05$",
                "$generic$",
                "$1$",
                "$argon2id$",
                "*",
                "$WP$",
                "$p$");
        Gen<String> bodies = Gens.strings(BCRYPT_ALPHABET + "-_=!$ é\n", 0, 70);
        return random -> prefixes.next(random) + bodies.next(random);
    }

    /**
     * phpass settings: {@code $P$} or {@code $H$}, a count character for 2^7 to 2^13 rounds, and a salt of at least
     * 8 bytes (one in five made of 2- and 3-byte characters).
     */
    static Gen<String> phpassSettings() {
        Gen<String> ids = Gens.elements("$P$", "$H$");
        Gen<String> counts = Gens.elements("5", "6", "7", "8", "9", "A", "B");
        Gen<String> salts = Gens.<String>frequency(
                weighted(4, Gens.strings(Phpass.ALPHABET, 8, 8)), weighted(1, Gens.strings("éßΩ€", 4, 4)));
        return random -> ids.next(random) + counts.next(random) + salts.next(random);
    }

    /** Application-password-like secrets (24 letters and digits) and arbitrary bytes. */
    static Gen<byte[]> secrets() {
        return Gens.<byte[]>frequency(
                weighted(2, Gens.strings(ALPHANUMERIC, 24, 24).map(PhpBytes::utf8)), weighted(1, Gens.bytes(0, 100)));
    }

    /** {@code hash} with the character at {@code index} replaced by a different character of {@code alphabet}. */
    static String replaceChar(String hash, int index, String alphabet, int pick) {
        char current = hash.charAt(index);
        char replacement = alphabet.charAt(Math.floorMod(pick, alphabet.length()));
        if (replacement == current) {
            replacement = alphabet.charAt(Math.floorMod(pick + 1, alphabet.length()));
        }
        return hash.substring(0, index) + replacement + hash.substring(index + 1);
    }
}
