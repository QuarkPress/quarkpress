package org.quarkpress.security.runtime;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.UserId;
import org.quarkpress.lib.wpkses.Kses;

/**
 * How a user's login, nicename and display name follow from the OIDC claims, as WordPress derives them when a user is
 * created. The {@code preferred_username} claim plays WordPress's login; {@code given_name} and {@code family_name}
 * play its first and last name.
 */
public final class UserNames {

    /** The longest nicename WordPress stores. */
    public static final int MAX_NICENAME = 50;

    /** The longest display name WordPress stores. */
    public static final int MAX_DISPLAY_NAME = 250;

    private UserNames() {}

    /**
     * WordPress's strict user-name sanitizing: tags removed (script and style elements with their content), accents
     * removed, percent-encoded octets and HTML entities removed, then only ASCII letters, digits, space, {@code _},
     * {@code .}, {@code -} and {@code @} kept, trimmed, and runs of spaces made single. Case is kept.
     */
    public static String sanitizeUserStrict(String raw) {
        Objects.requireNonNull(raw, "raw");
        String text = RemoveAccents.enUs(Kses.stripAllTags(raw, false));
        text = removeEntities(removePercentOctets(text));
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                pendingSpace = out.length() > 0;
            } else if (isStrictCharacter(c)) {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The login WordPress would give a user registering as {@code preferredUsername}; may be empty. */
    public static String login(String preferredUsername) {
        return sanitizeUserStrict(preferredUsername);
    }

    /**
     * The nicename WordPress derives from a login that strict sanitizing produced: its first 50 characters,
     * lower-cased, with dots made hyphens, characters other than letters, digits, {@code _} and {@code -} dropped,
     * runs of spaces and hyphens made one hyphen, and hyphens trimmed from both ends. May be empty.
     */
    public static String nicenameOfLogin(String login) {
        Objects.requireNonNull(login, "login");
        String cut = login.length() > MAX_NICENAME ? login.substring(0, MAX_NICENAME) : login;
        StringBuilder out = new StringBuilder(cut.length());
        boolean pendingHyphen = false;
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c == '.' || c == '-' || c == ' ') {
                pendingHyphen = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                if (pendingHyphen && out.length() > 0) {
                    out.append('-');
                }
                pendingHyphen = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The nicename before uniqueness, or empty where WordPress would refuse the user (empty login or nicename). */
    public static Optional<String> nicenameBase(String preferredUsername) {
        String login = login(preferredUsername);
        if (isPhpEmpty(login)) {
            return Optional.empty();
        }
        String nicename = nicenameOfLogin(login);
        return isPhpEmpty(nicename) ? Optional.empty() : Optional.of(nicename);
    }

    /**
     * The candidate WordPress tries when {@code base} is taken: the first {@code 49 - digits(suffix)} characters of the
     * base, a hyphen and the suffix (2, 3, ...). The cut base is not cleaned again, so a trailing hyphen doubles.
     */
    public static String withSuffix(String base, int suffix) {
        Objects.requireNonNull(base, "base");
        if (suffix < 2) {
            throw new IllegalArgumentException("a nicename suffix starts at 2: " + suffix);
        }
        String digits = Integer.toString(suffix);
        int keep = Math.min(base.length(), MAX_NICENAME - 1 - digits.length());
        return base.substring(0, keep) + "-" + digits;
    }

    /** The nicename of a user whose username gives none. */
    public static String fallbackNicename(UserId user) {
        return "user-" + user.value();
    }

    /**
     * The display name of a new user: "given family" when both are present, else the given name, else the family
     * name, else the preferred username, else the subject. Names are trimmed like PHP trims; blank counts as absent.
     * Stored as given (not HTML-escaped), cut to 250 characters.
     */
    public static String displayName(
            Optional<String> givenName,
            Optional<String> familyName,
            Optional<String> preferredUsername,
            String subject) {
        Objects.requireNonNull(subject, "subject");
        String first =
                givenName.map(UserNames::phpTrim).filter(n -> !isPhpEmpty(n)).orElse("");
        String last =
                familyName.map(UserNames::phpTrim).filter(n -> !isPhpEmpty(n)).orElse("");
        String name;
        if (!first.isEmpty() && !last.isEmpty()) {
            name = first + " " + last;
        } else if (!first.isEmpty()) {
            name = first;
        } else if (!last.isEmpty()) {
            name = last;
        } else {
            String username = preferredUsername.map(UserNames::phpTrim).orElse("");
            name = username.isEmpty() ? subject : username;
        }
        return cutCodePoints(name, MAX_DISPLAY_NAME);
    }

    /** PHP's {@code empty()} for text: the empty string and "0" both count as empty. */
    private static boolean isPhpEmpty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    /** PHP's trim: space, tab, line feed, carriage return, NUL and vertical tab from both ends. */
    public static String phpTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isPhpTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** The first {@code max} code points of {@code text}; a surrogate pair is never split. */
    private static String cutCodePoints(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static boolean isPhpTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    private static boolean isStrictCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '.'
                || c == '-'
                || c == '@';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Removes each {@code %} followed by two hexadecimal digits, left to right, in one pass. */
    private static String removePercentOctets(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '%' && isHexPair(text, i + 1)) {
                i += 3;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isHexPair(String text, int at) {
        return at + 1 < text.length() && isHex(text.charAt(at)) && isHex(text.charAt(at + 1));
    }

    /**
     * Removes each entity-like run: an {@code &}, at least one more character, up to and including the first {@code ;}
     * after it, with no line feed before that {@code ;}. One pass, left to right; a scan that finds no end proves that
     * no {@code &} before the place where it stopped can end either, so the text is read a bounded number of times.
     */
    private static String removeEntities(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int noEndBefore = -1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&' || i < noEndBefore) {
                out.append(c);
                i++;
                continue;
            }
            int end = -1;
            if (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                for (int q = i + 2; q < text.length(); q++) {
                    char d = text.charAt(q);
                    if (d == ';') {
                        end = q;
                        break;
                    }
                    if (d == '\n') {
                        noEndBefore = q;
                        break;
                    }
                }
                if (end < 0 && noEndBefore < i) {
                    noEndBefore = text.length();
                }
            }
            if (end < 0) {
                out.append(c);
                i++;
            } else {
                i = end + 1;
            }
        }
        return out.toString();
    }
}
