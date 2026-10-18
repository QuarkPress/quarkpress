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
