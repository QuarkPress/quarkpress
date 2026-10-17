package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies passwords stored by WordPress the way WordPress 7.1.3's {@code wp_check_password} does, reports the
 * stored format, and says when a stored hash should be replaced. Stateless and thread-safe.
 *
 * <p>Passwords are used as their UTF-8 bytes, never trimmed or normalized. Stored hashes are read as their UTF-8
 * bytes as well, so every length below is a byte count, as in PHP.
 */
public final class WpPasswords {

    /** Stored hashes of at most this many bytes are compared with the MD5 of the password, whatever they hold. */
    static final int SHORT_HASH_BYTES = 32;

    /** Longer passwords never verify, except against an MD5 hash (that rule comes first). */
    static final int MAX_PASSWORD_BYTES = 4096;

    /** The bcrypt cost PHP 8.4 uses when WordPress's filters set none; any other cost asks for a rehash. */
    static final int TARGET_COST = 12;

    private static final int BCRYPT_LENGTH = 60;
    private static final String WP_PREFIX = "$wp";
    private static final String FAST_HASH_PREFIX = "$generic$";
    private static final String PREHASH_MAC = "HmacSHA384";
    private static final byte[] PREHASH_KEY = "wp-sha384".getBytes(US_ASCII);
    private static final byte[] FAST_HASH_KEY = "wp_fast_hash_6.8+".getBytes(US_ASCII);
    private static final int FAST_HASH_BYTES = 30;

    private WpPasswords() {}

    /**
     * Whether {@code password} matches {@code storedHash}, decided as {@code wp_check_password} decides it with
     * WordPress's default configuration: a stored hash of 32 bytes or fewer is compared with the MD5 of the password;
     * otherwise a password over 4096 bytes fails; {@code $wp} hashes are bcrypt over the HMAC-SHA-384 pre-hash,
     * {@code $P$} hashes are phpass, and anything else must be {@code $2a$}/{@code $2b$}/{@code $2y$} bcrypt of the
     * raw password. The password is not trimmed, unslashed or normalized. Malformed hashes return {@code false}.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public static boolean check(String password, String storedHash) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(storedHash, "storedHash");
        return checkBytes(PhpBytes.utf8(password), PhpBytes.utf8(storedHash));
    }

    /**
     * {@link #check(String, String)} for a password given as the exact bytes received, for input that is not valid
     * UTF-8 text.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public static boolean check(byte[] password, String storedHash) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(storedHash, "storedHash");
        return checkBytes(password, PhpBytes.utf8(storedHash));
    }

    /**
     * {@link #check(String, String)}, and when that fails and {@code addslashes(password)} differs from the password,
     * {@code check} of that slashed form. WordPress hashes passwords set through its own screens and REST API, and
     * checks logins, in their magic-quoted form, so a user who chose {@code O'Brien} has a hash of {@code O\'Brien}.
     * Use this for logins.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public static boolean verify(String password, String storedHash) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(storedHash, "storedHash");
        byte[] raw = PhpBytes.utf8(password);
        byte[] hash = PhpBytes.utf8(storedHash);
        if (checkBytes(raw, hash)) {
            return true;
        }
        byte[] slashed = PhpBytes.addSlashes(raw);
        return slashed.length != raw.length && checkBytes(slashed, hash);
    }

    /**
     * Whether {@code secret} matches {@code storedHash} as {@code WP_Application_Passwords::check_password} decides
     * it: a {@code $generic$} hash is keyed BLAKE2b ({@code wp_fast_hash}) of the secret; any other hash goes to
     * {@link #check(String, String)}, as application passwords made before WordPress 6.8 were stored with
     * {@code wp_hash_password}. The secret is used as given: strip the spaces of the displayed
     * {@code abcd EFGH …} form first, as WordPress's login does.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public static boolean checkFastHash(String secret, String storedHash) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(storedHash, "storedHash");
        byte[] bytes = PhpBytes.utf8(secret);
        byte[] hash = PhpBytes.utf8(storedHash);
        if (!PhpBytes.startsWith(hash, FAST_HASH_PREFIX)) {
            return checkBytes(bytes, hash);
        }
        return MessageDigest.isEqual(fastHash(bytes), hash);
    }

    /**
     * The format of {@code storedHash}, decided in the order WordPress picks its verifier: 32 bytes or fewer first
     * ({@link HashType#MD5} for 32 lowercase hex digits, else {@link HashType#UNKNOWN}), then the case-sensitive
     * prefixes {@code $wp}, {@code $P$}, {@code $H$}, a 60-byte {@code $2a$}/{@code $2b$}/{@code $2x$}/{@code $2y$}
     * hash, and {@code $generic$}. Never throws; {@code null} is {@link HashType#UNKNOWN}. The type names the
     * verifier that applies, not a promise that the hash is well formed; {@code $2x$} is reported although
     * {@link #check(String, String)} refuses it.
     */
    public static HashType detect(String storedHash) {
        if (storedHash == null) {
            return HashType.UNKNOWN;
        }
        byte[] hash = PhpBytes.utf8(storedHash);
        if (hash.length <= SHORT_HASH_BYTES) {
            return PhpBytes.isLowercaseMd5Hex(hash) ? HashType.MD5 : HashType.UNKNOWN;
        }
        if (PhpBytes.startsWith(hash, WP_PREFIX)) {
            return HashType.WP_BCRYPT;
        }
        if (PhpBytes.startsWith(hash, "$P$")) {
            return HashType.PHPASS;
        }
        if (PhpBytes.startsWith(hash, "$H$")) {
            return HashType.PHPASS_PHPBB;
        }
        if (hash.length == BCRYPT_LENGTH && isBcryptId(hash)) {
            return HashType.BCRYPT;
        }
        if (PhpBytes.startsWith(hash, FAST_HASH_PREFIX)) {
            return HashType.WP_FAST_HASH;
        }
        return HashType.UNKNOWN;
    }

    /**
     * {@code wp_password_needs_rehash} with WordPress's default filters on PHP 8.4 (bcrypt, cost 12): {@code true}
     * for every hash except a {@code $wp} hash whose remaining 60 bytes are {@code $2y$} bcrypt of cost 12. The cost
     * is read as PHP reads it (leading whitespace and a sign are allowed; without digits PHP assumes 12). Call it
     * after a successful {@link #verify}, and hash the password anew in the identity provider when it is
     * {@code true}.
     *
     * @throws NullPointerException if {@code storedHash} is {@code null}
     */
    public static boolean needsRehash(String storedHash) {
        Objects.requireNonNull(storedHash, "storedHash");
        byte[] hash = PhpBytes.utf8(storedHash);
        if (!PhpBytes.startsWith(hash, WP_PREFIX)) {
            return true;
        }
        int rest = WP_PREFIX.length();
        if (hash.length - rest != BCRYPT_LENGTH || !PhpBytes.startsWith(hash, rest, "$2y$")) {
            return true;
        }
        return scannedCost(hash, rest + 4) != TARGET_COST;
    }

    /** The decision of {@code wp_check_password} on bytes, in WordPress's rule order. */
    static boolean checkBytes(byte[] password, byte[] hash) {
        if (hash.length <= SHORT_HASH_BYTES) {
            return MessageDigest.isEqual(hash, PhpBytes.md5Hex(password));
        }
        if (password.length > MAX_PASSWORD_BYTES) {
            return false;
        }
        if (PhpBytes.startsWith(hash, WP_PREFIX)) {
            return Bcrypt.verify(prehash(password), Arrays.copyOfRange(hash, WP_PREFIX.length(), hash.length));
        }
        if (PhpBytes.startsWith(hash, "$P$")) {
            return Phpass.verify(password, hash);
        }
        return Bcrypt.verify(password, hash);
    }

    /** The WordPress 6.8 pre-hash: standard Base64 of HMAC-SHA-384 keyed with {@code wp-sha384}, 64 ASCII bytes. */
    static byte[] prehash(byte[] password) {
        try {
            Mac mac = Mac.getInstance(PREHASH_MAC);
            mac.init(new SecretKeySpec(PREHASH_KEY, PREHASH_MAC));
            return Base64.getEncoder().encode(mac.doFinal(password));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK's " + PREHASH_MAC + " is unavailable", e);
        }
    }

    /** {@code wp_fast_hash}: {@code $generic$} and URL-safe unpadded Base64 of 30-byte keyed BLAKE2b. */
    static byte[] fastHash(byte[] secret) {
        byte[] digest = Blake2b.hash(FAST_HASH_KEY, secret, FAST_HASH_BYTES);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return (FAST_HASH_PREFIX + encoded).getBytes(US_ASCII);
    }

    /**
     * A {@code $wp$2y$} hash of {@code password} with the given cost and 16-byte salt. For tests only: QuarkPress
     * never writes WordPress hashes. Unlike {@code wp_hash_password} it does not trim.
     */
    static String wpBcryptHash(byte[] password, int cost, byte[] salt) {
        return WP_PREFIX + Bcrypt.hash(prehash(password), 'y', cost, salt);
    }

    /**
     * The number C's {@code sscanf("%ld")} reads at {@code from}: optional whitespace, an optional sign, then
     * decimal digits (saturated; only the comparison with 12 matters). Without digits the result is
     * {@link #TARGET_COST}, the value PHP keeps when the scan fails.
     */
    static long scannedCost(byte[] text, int from) {
        int i = from;
        while (i < text.length && isCSpace(text[i])) {
            i++;
        }
        boolean negative = false;
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-';
            i++;
        }
        if (i >= text.length || text[i] < '0' || text[i] > '9') {
            return TARGET_COST;
        }
        long value = 0;
        while (i < text.length && text[i] >= '0' && text[i] <= '9') {
            value = Math.min(value * 10 + (text[i] - '0'), Integer.MAX_VALUE);
            i++;
        }
        return negative ? -value : value;
    }

    /** C's isspace() in the default locale: space, tab, line feed, vertical tab, form feed, carriage return. */
    private static boolean isCSpace(byte b) {
        return b == ' ' || (b >= '\t' && b <= '\r');
    }

    private static boolean isBcryptId(byte[] hash) {
        return PhpBytes.startsWith(hash, "$2a$")
                || PhpBytes.startsWith(hash, "$2b$")
                || PhpBytes.startsWith(hash, "$2x$")
                || PhpBytes.startsWith(hash, "$2y$");
    }
}
