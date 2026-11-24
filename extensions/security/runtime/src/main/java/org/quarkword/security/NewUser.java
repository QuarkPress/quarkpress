package org.quarkword.security;

import java.util.Objects;
import java.util.Optional;
import org.quarkword.lib.wpkses.Emails;
import org.quarkword.lib.wpkses.Kses;

/**
 * A user to create in the identity provider and in QuarkWord, with the fields WordPress keeps of a user: login
 * ({@code user_login}), e-mail, display name, first and last name, and the stored password hash when the user keeps
 * their password.
 *
 * <p>The username is sent to the identity provider unchanged, which may refuse it. It has 1 to {@value #MAX_USERNAME}
 * characters, as WordPress's login, is not {@code 0} (WordPress counts it as empty) and does not start or end with
 * whitespace (WordPress trims a login; QuarkWord refuses rather than changing it).
 *
 * <p>The display, first and last name are plain text, not HTML: tags (script and style elements with their content)
 * are removed, runs of spaces, tabs and line breaks become one space and the ends are trimmed, as WordPress removes
 * them when it saves a user; nothing is HTML-escaped or decoded. A first or last name left empty is absent. An empty
 * display name ({@code ""} or {@code 0}) is derived from the names as WordPress derives it for a new user.
 *
 * <p>The e-mail is trimmed as PHP trims; a blank one is absent. An address WordPress's {@code is_email} refuses, which
 * its {@code sanitize_email} would change or empty, is refused rather than changed. Lengths count code points, except
 * the e-mail's, which counts UTF-16 units as a profile's does.
 */
public record NewUser(
        String username,
        Optional<String> email,
        String displayName,
        Optional<String> firstName,
        Optional<String> lastName,
        Optional<PasswordHash> password) {

    /** The longest username WordPress creates. */
    public static final int MAX_USERNAME = 60;

    /** The longest display name a profile stores. */
    public static final int MAX_DISPLAY_NAME = 250;

    /** The longest e-mail a profile stores (WordPress's {@code user_email} column). */
    public static final int MAX_EMAIL = ProfileClaims.MAX_EMAIL;

    public NewUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(password, "password");
        displayName = plainText(displayName);
        firstName = firstName.map(NewUser::plainText).filter(name -> !name.isEmpty());
        lastName = lastName.map(NewUser::plainText).filter(name -> !name.isEmpty());
        email = email.map(NewUser::phpTrim).filter(address -> !address.isEmpty());
        if (username.isEmpty() || username.equals("0") || username.isBlank()) {
            throw new IllegalArgumentException("a username must not be empty");
        }
        if (padded(username)) {
            throw new IllegalArgumentException("a username must not start or end with whitespace");
        }
        int length = username.codePointCount(0, username.length());
        if (length > MAX_USERNAME) {
            throw new IllegalArgumentException("a username has at most " + MAX_USERNAME + " characters: " + length);
        }
        int display = displayName.codePointCount(0, displayName.length());
        if (display > MAX_DISPLAY_NAME) {
            throw new IllegalArgumentException(
                    "a display name has at most " + MAX_DISPLAY_NAME + " characters: " + display);
        }
        if (email.isPresent()) {
            if (email.get().length() > MAX_EMAIL) {
                // counted as ProfileClaims counts it, so a user the identity provider accepted always gets its profile
                throw new IllegalArgumentException("an e-mail has at most " + MAX_EMAIL + " characters: "
                        + email.get().length());
            }
            if (!Emails.isEmail(email.get())) {
                throw new IllegalArgumentException("an e-mail must be a valid address");
            }
        }
    }

    /** A user without e-mail, names or password hash. */
    public static NewUser of(String username, String displayName) {
        return new NewUser(
                username, Optional.empty(), displayName, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** WordPress's {@code wp_strip_all_tags} with line breaks removed. */
    private static String plainText(String text) {
        return Kses.stripAllTags(text, true);
    }

    /** PHP's trim: space, tab, line feed, carriage return, NUL and vertical tab from both ends. */
    private static String phpTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && phpTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && phpTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean phpTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    /** Whether {@code text} starts or ends with whitespace or a character PHP's {@code trim} removes. */
    private static boolean padded(String text) {
        return trimmed(text.charAt(0)) || trimmed(text.charAt(text.length() - 1));
    }

    private static boolean trimmed(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\0';
    }
}
