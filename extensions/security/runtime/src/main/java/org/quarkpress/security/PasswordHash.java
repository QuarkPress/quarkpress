package org.quarkpress.security;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.quarkpress.lib.wppassword.WpPasswords;

/**
 * A password hash a user brings from another system, to be stored by the identity provider so that the user keeps
 * their password. It is checked by its form only and never printed: {@link #toString()} leaves the value out,
 * Jackson skips every property of this type, and where Jackson writes one anyway (inside an {@code Optional} or a
 * collection) it writes the {@link #toString()} text.
 */
@JsonIgnoreType
public final class PasswordHash {

    /** The longest value WordPress's {@code user_pass} column holds, in bytes. */
    public static final int MAX_BYTES = 255;

    /** The algorithm name of a WordPress hash, as the identity provider's verifier knows it. */
    public static final String WORDPRESS = "wordpress";

    private final String algorithm;

    private final String value;

    private PasswordHash(String algorithm, String value) {
        this.algorithm = algorithm;
        this.value = value;
    }

    /**
     * A hash from WordPress's {@code user_pass} column: {@code $wp$} bcrypt, {@code $P$} phpass, plain bcrypt or
     * 32-character MD5, as WordPress's login checks it, well formed and within the bounds QuarkPress verifies.
     *
     * @throws IllegalArgumentException for any other value, which no password could match after the import: more
     *     than 255 bytes, blank, {@code *}, phpBB's {@code $H$}, an application password's {@code $generic$},
     *     {@code $2x$} and other crypt forms, bcrypt cost above 16, phpass counts above {@code I}; the message never
     *     carries the value
     */
    public static PasswordHash wordpress(String userPass) {
        Objects.requireNonNull(userPass, "userPass");
        if (userPass.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || !WpPasswords.verifiable(userPass)) {
            throw new IllegalArgumentException("not a WordPress password hash");
        }
        return new PasswordHash(WORDPRESS, userPass);
    }

    /** The algorithm name, {@value #WORDPRESS} for a WordPress hash. */
    public String algorithm() {
        return algorithm;
    }

    /** The stored hash, to hand to the identity provider only. */
    public String value() {
        return value;
    }

    @Override
    @JsonValue
    public String toString() {
        return "PasswordHash[" + algorithm + ", redacted]";
    }
}
