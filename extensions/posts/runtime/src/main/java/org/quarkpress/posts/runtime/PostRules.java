package org.quarkpress.posts.runtime;

import java.util.Set;
import org.quarkpress.core.WordPressKeys;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostTypes;

/** Field rules of WordPress's post save that need no database. */
public final class PostRules {

    /** Statuses that get no generated slug and no uniqueness check. */
    public static final Set<String> NO_SLUG_STATUSES = Set.of("draft", "pending", "auto-draft");

    private static final Set<String> ATTACHMENT_STATUSES = Set.of("inherit", "private", "trash", "auto-draft");

    private static final int MAX_KEY = 20;
    private static final int MAX_TEXT = 255;

    /** The bytes a {@code text} column holds (the title and the excerpt), as MySQL counts them in UTF-8. */
    static final int MAX_TEXT_BYTES = 65_535;

    private PostRules() {}

    /** PHP's {@code empty()} for text: "" and "0". */
    public static boolean phpEmpty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    /** The type as a key; empty after sanitizing (PHP sense) means {@code post}. */
    public static String type(String given) {
        String type = WordPressKeys.sanitize(given);
        if (type.equals("revision")) {
            throw PostException.invalidPostType(type);
        }
        return phpEmpty(type) ? "post" : type;
    }

    /**
     * The status as a key; empty after sanitizing (PHP sense) means {@code draft};
     * an attachment keeps only inherit, private, trash and auto-draft.
     */
    public static String status(String given, String type) {
        String status = WordPressKeys.sanitize(given);
        if (phpEmpty(status)) {
            status = "draft";
        }
        if (type.equals("attachment") && !ATTACHMENT_STATUSES.contains(status)) {
            status = "inherit";
        }
        return status;
    }

    /** Whether the save is refused as empty: not an attachment, the three fields empty, the three supported. */
    public static boolean maybeEmpty(String type, String title, String content, String excerpt, PostTypes types) {
        return !type.equals("attachment")
                && phpEmpty(content)
                && phpEmpty(title)
                && phpEmpty(excerpt)
                && types.supports(type, "editor")
                && types.supports(type, "title")
                && types.supports(type, "excerpt");
    }

    /** The type's comment status: closed for pages and types without comments, else the site's default. */
    public static String defaultCommentStatus(String type, PostTypes types, String option) {
        return !type.equals("page") && types.supports(type, "comments") ? option : "closed";
    }

    /** The type's ping status: closed for pages and types without trackbacks, else the site's default. */
    public static String defaultPingStatus(String type, PostTypes types, String option) {
        return !type.equals("page") && types.supports(type, "trackbacks") ? option : "closed";
    }

    /** A comment status as a key; empty means the type's default on insert and closed on update. */
    public static String commentStatus(String value, boolean update, String typeDefault) {
        String status = WordPressKeys.sanitize(value);
        if (phpEmpty(status)) {
            return update ? "closed" : typeDefault;
        }
        return status;
    }

    /** A ping status as a key; empty means the type's default. */
    public static String pingStatus(String value, String typeDefault) {
        String status = WordPressKeys.sanitize(value);
        return phpEmpty(status) ? typeDefault : status;
    }

    /** The stored password: as given, except none for a private post. */
    public static String password(String password, String status) {
        return status.equals("private") ? "" : password;
    }

    /**
     * The plain permalink of a post of {@code type} with {@code id} on the site whose home is {@code home}, cut to
     * the 255 characters the column holds.
     */
    public static String guid(String home, String type, long id) {
        String guid = switch (type) {
            case "post" -> home + "/?p=" + id;
            case "page" -> home + "/?page_id=" + id;
            case "attachment" -> home + "/?attachment_id=" + id;
            default -> home + "/?post_type=" + type + "&p=" + id;
        };
        return guid.codePointCount(0, guid.length()) <= MAX_TEXT
                ? guid
                : guid.substring(0, guid.offsetByCodePoints(0, MAX_TEXT));
    }

    /** Refuses values longer than their columns, as WordPress's database write refuses them. */
    public static void requireStorable(
            String type,
            String status,
            String slug,
            String title,
            String excerpt,
            String commentStatus,
            String pingStatus,
            String password,
            String guid,
            boolean update) {
        check(type, MAX_KEY, "type", update);
        check(status, MAX_KEY, "status", update);
        check(commentStatus, MAX_KEY, "comment_status", update);
        check(pingStatus, MAX_KEY, "ping_status", update);
        check(password, MAX_TEXT, "password", update);
        check(guid, MAX_TEXT, "guid", update);
        if (Slugs.byteLength(slug) > Slugs.MAX_BYTES) {
            throw PostException.dbError(update, "the slug is longer than " + Slugs.MAX_BYTES + " bytes");
        }
        checkBytes(title, "title", update);
        checkBytes(excerpt, "excerpt", update);
    }

    private static void checkBytes(String value, String column, boolean update) {
        if (utf8Length(value) > MAX_TEXT_BYTES) {
            throw PostException.dbError(update, "the " + column + " is longer than " + MAX_TEXT_BYTES + " bytes");
        }
    }

    /** The length of the text's UTF-8 form, without encoding it. */
    static long utf8Length(String text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            bytes += codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static void check(String value, int max, String column, boolean update) {
        if (value.codePointCount(0, value.length()) > max) {
            throw PostException.dbError(update, "the " + column + " is longer than " + max + " characters");
        }
    }
}
