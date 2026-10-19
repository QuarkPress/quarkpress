package org.quarkpress.testing.wpscenario;

import java.util.List;

/**
 * What a scenario may contain: inputs both sides can be compared on. WordPress's bootstrap would prepare a request's
 * host and path before its lookup (lower case, ports, query, backslashes, admin paths); the driver passes them to the
 * lookup directly, so scenarios hold only hosts and paths the preparation leaves unchanged apart from case.
 * The checks are whitelists, so they refuse more than ports, queries, backslashes, {@code %}, {@code /wp-admin/}
 * and HTML-special title characters: also any other punctuation (such as {@code ~ + , ! : @}) and non-ASCII
 * characters in hosts, domains and paths, underscores in hosts and domains, and titles over 100 characters.
 */
final class ScenarioRules {

    /** The default options a scenario may read: the ones both sides store for a new site. */
    static final List<String> OPTIONS = List.of(
            "blogname",
            "blogdescription",
            "timezone_string",
            "gmt_offset",
            "date_format",
            "time_format",
            "start_of_week",
            "posts_per_page",
            "default_category",
            "show_on_front",
            "page_on_front",
            "page_for_posts",
            "default_comment_status",
            "default_ping_status");

    private ScenarioRules() {}

    /** The network options a scenario may set: add_new_users, and the plugins entry of menu_items. */
    static final List<String> NETWORK_FLAGS = List.of("add_new_users", "menu_plugins");

    /** The site options a scenario may set. */
    static final List<String> SITE_SETTINGS = List.of("link_manager_enabled");

    /**
     * A username both sides can create: 1 to 60 characters, no control characters, not WordPress's installed
     * {@code admin}. A user without names shows the username as display name; WordPress shows its login instead, so
     * such a username must be one strict sanitizing keeps (ASCII letters, digits, {@code _ . - @}, single inner
     * spaces).
     */
    static void requireUsername(String username, boolean withoutNames) {
        if (username.isEmpty() || username.length() > 60) {
            throw new IllegalArgumentException("a scenario username has 1 to 60 characters: " + username);
        }
        for (int i = 0; i < username.length(); i++) {
            if (username.charAt(i) < 0x20 || username.charAt(i) == 0x7F) {
                throw new IllegalArgumentException("a scenario username holds no control characters");
            }
        }
        if (lowerAscii(username.strip()).equals("admin")) {
            throw new IllegalArgumentException("the username admin belongs to the WordPress installation's own user");
        }
        if (username.startsWith(" ")
                || username.endsWith(" ")
                || "<>&%".chars().anyMatch(c -> username.indexOf(c) >= 0)) {
            throw new IllegalArgumentException(
                    "a scenario username holds none of < > & % and no surrounding spaces, which WordPress would"
                            + " strip: " + username);
        }
        if (isFallbackNicename(username)) {
            throw new IllegalArgumentException(
                    "a scenario username does not read as QuarkPress's fallback nicename user-<number>: " + username);
        }
        if (withoutNames && !keptByStrictSanitizing(username)) {
            throw new IllegalArgumentException("a user without names needs a username WordPress keeps as it is:"
                    + " ASCII letters, digits, _ . - @ and single inner spaces: " + username);
        }
    }

    /** Whether a username becomes the nicename {@code user-<digits>} (with optional {@code -<digits>} suffixes). */
    private static boolean isFallbackNicename(String username) {
        String slug = lowerAscii(username).replace(' ', '-').replace('.', '-');
        if (!slug.startsWith("user-")) {
            return false;
        }
        boolean digit = false;
        for (int i = 5; i < slug.length(); i++) {
            char c = slug.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == '-' && digit && i + 1 < slug.length()) {
                digit = false;
            } else {
                return false;
            }
        }
        return digit;
    }

    /** A given or family name both sides store alike: WordPress escapes and trims names on save. */
    static void requireName(String name) {
        boolean ok = name.length() <= 100 && name.equals(name.strip()) && !name.contains("  ");
        for (int i = 0; ok && i < name.length(); i++) {
            char c = name.charAt(i);
            ok = c >= 0x20 && c != 0x7F && "&<>\"'%\\".indexOf(c) < 0;
        }
        if (!ok) {
            throw new IllegalArgumentException("a scenario name holds no HTML-special characters, no control"
                    + " characters and no surrounding or doubled spaces: " + name);
        }
    }

    /** A capability or role key; {@code emptyAllowed} for a role meaning "no role". */
    static void requireKey(String key, boolean emptyAllowed) {
        if ((key.isEmpty() && !emptyAllowed) || key.length() > 60 || !onlyOf(key, "_-", true)) {
            throw new IllegalArgumentException(
                    "a scenario capability or role key holds only letters, digits, _ and -: " + key);
        }
    }

    static void requireNetworkFlag(String name) {
        if (!NETWORK_FLAGS.contains(name)) {
            throw new IllegalArgumentException("a scenario network flag is add_new_users or menu_plugins: " + name);
        }
    }

    static void requireSiteSetting(String name) {
        if (!SITE_SETTINGS.contains(name)) {
            throw new IllegalArgumentException("a scenario site setting is link_manager_enabled: " + name);
        }
    }

    static void requireId(String id) {
        if (id.isEmpty() || !onlyOf(id, "-", false)) {
            throw new IllegalArgumentException("a scenario id holds only lower-case letters, digits and -: " + id);
        }
    }

    static void requireDomain(String domain) {
        if (!onlyOf(domain, "-.", true)) {
            throw new IllegalArgumentException("a scenario domain holds only letters, digits, - and .: " + domain);
        }
    }

    static void requireSitePath(String path) {
        if (!onlyOf(path, "-_/", true)) {
            throw new IllegalArgumentException("a scenario site path holds only letters, digits, - _ and /: " + path);
        }
    }

    static void requireHost(String host) {
        if (host.isEmpty() || !onlyOf(host, "-.", true)) {
            throw new IllegalArgumentException("a scenario host holds only letters, digits, - and .: " + host);
        }
    }

    static void requireLookupPath(String path) {
        if (!path.startsWith("/") || !onlyOf(path, "-_./", true)) {
            throw new IllegalArgumentException(
                    "a scenario lookup path starts with / and holds only letters, digits, - _ . and /: " + path);
        }
        if (lowerAscii(path).contains("/wp-admin/")) {
            throw new IllegalArgumentException("a scenario lookup path does not hold /wp-admin/: " + path);
        }
    }

    static void requireTitle(String title) {
        for (int i = 0; i < title.length(); i++) {
            if ("&<>\"'\\".indexOf(title.charAt(i)) >= 0) {
                throw new IllegalArgumentException(
                        "a scenario title holds no HTML-special characters or backslashes: " + title);
            }
        }
        if (title.length() > 100) {
            throw new IllegalArgumentException("a scenario title has at most 100 characters");
        }
    }

    static void requireOption(String name) {
        if (!OPTIONS.contains(name)) {
            throw new IllegalArgumentException("a scenario reads only these options: " + String.join(", ", OPTIONS));
        }
    }

    static String lowerAscii(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }

    private static boolean keptByStrictSanitizing(String username) {
        if (username.startsWith(" ") || username.endsWith(" ") || username.contains("  ")) {
            return false;
        }
        return onlyOf(username, "_.-@ ", true);
    }

    private static boolean onlyOf(String text, String punctuation, boolean upperCase) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || (upperCase && c >= 'A' && c <= 'Z')
                    || punctuation.indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
