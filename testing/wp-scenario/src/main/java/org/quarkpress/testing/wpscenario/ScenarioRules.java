package org.quarkpress.testing.wpscenario;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

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

    /** The main-site options a post scenario may set. */
    static final List<String> POST_OPTIONS = List.of(
            "timezone_string",
            "gmt_offset",
            "permalink_structure",
            "posts_per_page",
            "default_comment_status",
            "default_ping_status",
            "show_on_front");

    static void requirePostOption(String name) {
        if (!POST_OPTIONS.contains(name)) {
            throw new IllegalArgumentException(
                    "a scenario sets only these options: " + String.join(", ", POST_OPTIONS) + "; not " + name);
        }
    }

    /**
     * Post text both sides store alike: at most 2,000 characters, no control characters but tab and line feed, and no
     * characters outside the Basic Multilingual Plane (no surrogates).
     */
    static void requirePostText(String text) {
        if (text.length() > 2_000) {
            throw new IllegalArgumentException("a scenario post text has at most 2,000 characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n') || c == 0x7F || Character.isSurrogate(c)) {
                throw new IllegalArgumentException(
                        "a scenario post text holds no control characters but tab and line feed, and no surrogates");
            }
        }
    }

    /**
     * A post date both sides store alike, far from any run: a real {@code Y-m-d H:i:s} date before 2025 or from 2090
     * on (the zero date is not a date a scenario gives).
     */
    static void requirePostDate(String text) {
        LocalDateTime date;
        try {
            date = PostRecords.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("a scenario post date is a real Y-m-d H:i:s date: " + text, e);
        }
        if ((date.getYear() >= 2025 && date.getYear() < 2090) || date.getYear() < 1000) {
            throw new IllegalArgumentException(
                    "a scenario post date is Y-m-d H:i:s before 2025 or from 2090 on, away from the run: " + text);
        }
    }

    /**
     * A typed slug that reads as a post id is 1000 or more: WordPress numbers the main site's posts from a few, so a
     * smaller one would name a post on WordPress's side only.
     */
    static void requirePostSlug(String slug) {
        long number = PostRecords.idNumber(slug);
        if (number >= 0 && number < 1000) {
            throw new IllegalArgumentException(
                    "a scenario slug that is a number is 1000 or more, beyond WordPress's post ids: " + slug);
        }
    }

    /** A reference that may be 0 for none: an author, a parent, a front page, or the acting user. */
    static void requireReference(int reference) {
        if (reference < 0) {
            throw new IllegalArgumentException("a scenario reference is 0 (none) or positive: " + reference);
        }
    }

    /** The post an operation acts on: an insertion index from 1. */
    static void requirePost(int post) {
        if (post < 1) {
            throw new IllegalArgumentException("a scenario post operation names a post: 1 or more, not " + post);
        }
    }

    static final List<String> REVISION_FIELDS = List.of("title", "content", "excerpt");
    static final List<String> REVISION_META_KEYS = List.of("footnotes");
    static final List<String> AUTOSAVE_MODES = List.of("include", "only", "exclude");

    /** {@code r<k>} (k from 1): a revision row by creation order on the site during the scenario. */
    static void requireRevisionRef(String ref) {
        if (revisionIndex(ref) < 1) {
            throw new IllegalArgumentException("a scenario revision reference is r<k> (k from 1): " + ref);
        }
    }

    /** The k of an {@code r<k>} reference; 0 for any other text. */
    static int revisionIndex(String ref) {
        if (ref.length() < 2 || ref.length() > 6 || ref.charAt(0) != 'r' || ref.charAt(1) == '0') {
            return 0;
        }
        int k = 0;
        for (int i = 1; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            k = k * 10 + (c - '0');
        }
        return k;
    }

    static void requireRevisionField(String field) {
        if (!REVISION_FIELDS.contains(field)) {
            throw new IllegalArgumentException("a scenario restores title, content or excerpt: " + field);
        }
    }

    static void requireRevisionMetaKey(String key) {
        if (!REVISION_META_KEYS.contains(key)) {
            throw new IllegalArgumentException("a scenario's revisioned meta key is footnotes: " + key);
        }
    }

    /** The post meta keys {@code update_post_meta} may set: the revisioned one, and the trash time of a purge. */
    static final List<String> POST_META_KEYS = List.of("footnotes", "_wp_trash_meta_time");

    static void requirePostMetaKey(String key) {
        if (!POST_META_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "a scenario's post meta key is one of " + String.join(", ", POST_META_KEYS) + ": " + key);
        }
    }

    /** A Unix time in seconds as WordPress stores it in meta: 1 to 10 ASCII digits. */
    static void requireUnixSeconds(String value) {
        boolean ok = !value.isEmpty() && value.length() <= 10;
        for (int i = 0; ok && i < value.length(); i++) {
            ok = value.charAt(i) >= '0' && value.charAt(i) <= '9';
        }
        if (!ok) {
            throw new IllegalArgumentException("a scenario's Unix time in seconds is 1 to 10 digits: " + value);
        }
    }

    /** The user of an autosave or a restore: WordPress has no system autosave, so user 0 is refused. */
    static void requireAutosaveUser(int as) {
        requireReference(as);
        if (as == 0) {
            throw new IllegalArgumentException(
                    "a scenario autosaves and restores as a created user: WordPress has no system autosave");
        }
    }

    /** The taxonomies term scenarios use; genre is the custom one both sides register. */
    static final List<String> TERM_TAXONOMIES = List.of("category", "post_tag", "genre");

    /** The hierarchical ones among them: only these take a parent. */
    static final List<String> HIERARCHICAL_TAXONOMIES = List.of("category", "genre");

    static void requireTermTaxonomy(String taxonomy) {
        if (!TERM_TAXONOMIES.contains(taxonomy)) {
            throw new IllegalArgumentException(
                    "a scenario's taxonomy is one of " + String.join(", ", TERM_TAXONOMIES) + ": " + taxonomy);
        }
    }

    /** {@code t<k>} (k from 1), {@code default}, and, where a parent is meant, {@code none}. */
    static void requireTermRef(String ref, boolean noneAllowed) {
        if (ref.equals("default") || (noneAllowed && ref.equals("none")) || termIndex(ref) > 0) {
            return;
        }
        throw new IllegalArgumentException("a scenario term reference is t<k> (k from 1) or default"
                + (noneAllowed ? " or none" : "") + ": " + ref);
    }

    /** The k of a {@code t<k>} reference; 0 for any other text. */
    static int termIndex(String ref) {
        if (ref.length() < 2 || ref.length() > 6 || ref.charAt(0) != 't' || ref.charAt(1) == '0') {
            return 0;
        }
        int k = 0;
        for (int i = 1; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            k = k * 10 + (c - '0');
        }
        return k;
    }

    /**
     * Term text both sides store alike, within what the main suite compares: printable ASCII, tab and line feed, no
     * backslash (so slashing never matters), at most 300 characters; a text that is a number is 1000 or more.
     */
    static void requireTermText(String text) {
        if (text.length() > 300) {
            throw new IllegalArgumentException("a scenario's term text has at most 300 characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean printable = (c >= 0x20 && c <= 0x7E) || c == '\t' || c == '\n';
            if (!printable || c == '\\') {
                throw new IllegalArgumentException(
                        "a scenario's term text is printable ASCII, tab or line feed, without backslashes");
            }
        }
        long number = PostRecords.idNumber(text.trim());
        if (number >= 0 && number < 1000) {
            throw new IllegalArgumentException(
                    "a scenario term name or slug that is a number is 1000 or more: " + text);
        }
    }

    /**
     * A term name or slug (or a text compared with them): term text whose slug form does not start with a number below
     * 1000. WordPress numbers the main site's terms from a few, and a slug such as {@code 3-days} would read as the
     * slug of term 3 on one side only.
     */
    static void requireTermName(String text) {
        requireTermText(text);
        int digits = leadingDigitsOfSlug(text);
        if (digits > 0 && digits < 4) {
            throw new IllegalArgumentException(
                    "a scenario term name or slug that starts with a number starts with 1000 or more: " + text);
        }
    }

    /**
     * The length of the digit run that starts a text's slug form, 0 when the slug form starts with a letter, with 0 or
     * is empty. The slug form drops tags (not a {@code <} that whitespace follows), entities, percent-octets and other
     * punctuation before the first letter or digit.
     */
    private static int leadingDigitsOfSlug(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // strip_tags keeps a '<' that whitespace follows as text
            boolean tag = c == '<' && i + 1 < text.length() && " \t\n\u000B\f\r".indexOf(text.charAt(i + 1)) < 0;
            int skip = tag ? text.indexOf('>', i) : c == '&' ? text.indexOf(';', i) : -1;
            if (skip > i) {
                i = skip + 1;
            } else if (c == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                i += 3;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return 0;
            } else if (c >= '0' && c <= '9') {
                if (c == '0') {
                    return 0;
                }
                int end = i;
                while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                    end++;
                }
                return end - i;
            } else {
                i++;
            }
        }
        return 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** The taxonomies with a default term: only these resolve the reference {@code default}. */
    static final List<String> DEFAULT_TERM_TAXONOMIES = List.of("category", "genre");

    /** {@code default} only where the taxonomy it resolves in has a default term. */
    static void requireDefaultIn(String taxonomy, String ref) {
        if (ref.equals("default") && !DEFAULT_TERM_TAXONOMIES.contains(taxonomy)) {
            throw new IllegalArgumentException("a scenario names default only in a taxonomy with a default term ("
                    + String.join(", ", DEFAULT_TERM_TAXONOMIES) + "): " + taxonomy);
        }
    }

    /** A parent only in a hierarchical taxonomy. */
    static void requireTermParent(String taxonomy, Optional<String> parent) {
        if (parent.isPresent() && !HIERARCHICAL_TAXONOMIES.contains(taxonomy)) {
            throw new IllegalArgumentException("a scenario gives a parent only in a hierarchical taxonomy ("
                    + String.join(", ", HIERARCHICAL_TAXONOMIES) + "): " + taxonomy);
        }
        parent.ifPresent(ref -> requireTermRef(ref, true));
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
