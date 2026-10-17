package org.quarkpress.lib.wppassword;

/** The stored-hash formats {@link WpPasswords#detect} tells apart. */
public enum HashType {
    /** 32 lowercase hexadecimal digits: unsalted MD5, written by WordPress before 2.5. */
    MD5,
    /** {@code $wp$2y$...}: bcrypt over an HMAC-SHA-384 pre-hash, written by WordPress 6.8 and later. */
    WP_BCRYPT,
    /** {@code $P$...}: a phpass portable hash, written by WordPress 2.5 to 6.7. */
    PHPASS,
    /** {@code $H$...}: the phpBB flavour of phpass. WordPress core does not accept it. */
    PHPASS_PHPBB,
    /** {@code $2a$}, {@code $2b$}, {@code $2x$} or {@code $2y$} bcrypt of 60 characters, as plugins stored it. */
    BCRYPT,
    /** {@code $generic$...}: keyed BLAKE2b, used for application passwords and keys since WordPress 6.8. */
    WP_FAST_HASH,
    /** Anything else, including every other string of 32 bytes or fewer. */
    UNKNOWN
}
