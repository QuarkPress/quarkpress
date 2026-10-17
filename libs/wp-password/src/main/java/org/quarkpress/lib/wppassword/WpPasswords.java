package org.quarkpress.lib.wppassword;

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

    private static final int BCRYPT_LENGTH = 60;
    private static final String WP_PREFIX = "$wp";
    private static final String FAST_HASH_PREFIX = "$generic$";

    private WpPasswords() {}

    /**
     * The format of {@code storedHash}, decided in the order WordPress picks its verifier: 32 bytes or fewer first
     * ({@link HashType#MD5} for 32 lowercase hex digits, else {@link HashType#UNKNOWN}), then the case-sensitive
     * prefixes {@code $wp}, {@code $P$}, {@code $H$}, a 60-byte {@code $2a$}/{@code $2b$}/{@code $2x$}/{@code $2y$}
     * hash, and {@code $generic$}. Never throws; {@code null} is {@link HashType#UNKNOWN}. The type names the
     * verifier that applies, not a promise that the hash is well formed; {@code $2x$} is reported although
     * {@code check} refuses it.
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

    private static boolean isBcryptId(byte[] hash) {
        return PhpBytes.startsWith(hash, "$2a$")
                || PhpBytes.startsWith(hash, "$2b$")
                || PhpBytes.startsWith(hash, "$2x$")
                || PhpBytes.startsWith(hash, "$2y$");
    }
}
