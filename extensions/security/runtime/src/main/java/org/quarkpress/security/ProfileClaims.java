package org.quarkpress.security;

import java.util.Objects;
import java.util.Optional;

/**
 * What provisioning reads from an identity: the OIDC subject ({@code sub}) and, when the token carries them,
 * {@code preferred_username}, {@code given_name}, {@code family_name} and a verified {@code email}.
 */
public record ProfileClaims(
        String subject,
        Optional<String> preferredUsername,
        Optional<String> givenName,
        Optional<String> familyName,
        Optional<String> email) {

    /** The longest subject a profile stores. */
    public static final int MAX_SUBJECT = 255;

    /** The longest e-mail a profile stores (WordPress's {@code user_email} column). */
    public static final int MAX_EMAIL = 100;

    public ProfileClaims {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(preferredUsername, "preferredUsername");
        Objects.requireNonNull(givenName, "givenName");
        Objects.requireNonNull(familyName, "familyName");
        Objects.requireNonNull(email, "email");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("an OIDC subject must not be blank");
        }
        if (Character.isWhitespace(subject.charAt(0)) || Character.isWhitespace(subject.charAt(subject.length() - 1))) {
            // MySQL's PAD SPACE collation would match "abc " to the profile of "abc"
            throw new IllegalArgumentException("an OIDC subject must not start or end with whitespace");
        }
        if (subject.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException(
                    "an OIDC subject has at most " + MAX_SUBJECT + " characters: " + subject.length());
        }
        if (email.isPresent() && email.get().length() > MAX_EMAIL) {
            throw new IllegalArgumentException("an e-mail has at most " + MAX_EMAIL + " characters: "
                    + email.get().length());
        }
    }

    /** Claims without e-mail. */
    public ProfileClaims(
            String subject,
            Optional<String> preferredUsername,
            Optional<String> givenName,
            Optional<String> familyName) {
        this(subject, preferredUsername, givenName, familyName, Optional.empty());
    }

    public static ProfileClaims subjectOnly(String subject) {
        return new ProfileClaims(subject, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
