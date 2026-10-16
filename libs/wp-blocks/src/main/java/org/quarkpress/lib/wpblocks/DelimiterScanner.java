package org.quarkpress.lib.wpblocks;

/**
 * Recognises block delimiters at a given index. Anything else is text.
 *
 * <p>Shape: {@code <!--}, whitespace, an optional {@code /}, {@code wp:}, a name, whitespace, optional attribute
 * JSON followed by whitespace, an optional {@code /}, then {@code -->}. A {@code /} before {@code -->} makes the
 * delimiter void whether or not it started with {@code /}; otherwise a leading {@code /} makes it a closer, whose
 * attributes are ignored. Whitespace is space, tab, line feed, vertical tab, form feed and carriage return.
 */
final class DelimiterScanner {

    static final String COMMENT_OPEN = "<!--";
    private static final String COMMENT_CLOSE = "-->";
    private static final String VOID_CLOSE = "/-->";
    private static final String WP = "wp:";

    enum Kind {
        OPENER,
        VOID,
        CLOSER
    }

    /**
     * A recognised delimiter occupying {@code [start, end)} of the input. {@code rawAttributes} is the attribute
     * text of an opener or a void delimiter, or null; a closer's attribute text is not kept apart.
     * {@code attributesDecodable} is false when whitespace between the attribute JSON and the close contains a
     * form feed or vertical tab: WordPress hands that whitespace to a JSON decoder that rejects those two.
     */
    record Delimiter(Kind kind, String name, String rawAttributes, boolean attributesDecodable, int start, int end) {}

    private DelimiterScanner() {}

    /**
     * Returns the delimiter starting exactly at {@code start}, or null if there is none. {@code ends} is shared
     * across calls on the same input, which keeps a whole scan linear. Attributes run from the {@code {} after the
     * name to the first {@code }} followed by whitespace and {@code -->} or {@code /-->}.
     */
    static Delimiter tryRead(String s, int start, AttributeEnds ends) {
        if (!s.startsWith(COMMENT_OPEN, start)) {
            return null;
        }
        int pos = skipWs(s, start + COMMENT_OPEN.length());
        if (pos == start + COMMENT_OPEN.length()) {
            return null; // whitespace after <!-- is mandatory
        }
        boolean slashFirst = pos < s.length() && s.charAt(pos) == '/';
        if (slashFirst) {
            pos++;
        }
        if (!s.startsWith(WP, pos)) {
            return null;
        }
        pos += WP.length();
        int nameStart = pos;
        pos = readName(s, pos);
        if (pos < 0) {
            return null;
        }
        String name = BlockNames.qualify(s.substring(nameStart, pos));

        int afterName = skipWs(s, pos);
        if (afterName == pos) {
            return null; // whitespace after the name is mandatory
        }
        pos = afterName;

        String raw = null;
        boolean decodable = true;
        if (pos < s.length() && s.charAt(pos) == '{') {
            int term = ends.terminatorFrom(pos);
            if (term < 0) {
                return null;
            }
            raw = s.substring(pos, term + 1);
            pos = skipWs(s, term + 1);
            for (int i = term + 1; i < pos && decodable; i++) {
                decodable = s.charAt(i) != '\f' && s.charAt(i) != '\u000B';
            }
        }
        if (s.startsWith(VOID_CLOSE, pos)) {
            return new Delimiter(Kind.VOID, name, raw, decodable, start, pos + VOID_CLOSE.length());
        }
        if (!s.startsWith(COMMENT_CLOSE, pos)) {
            return null;
        }
        int end = pos + COMMENT_CLOSE.length();
        return slashFirst
                ? new Delimiter(Kind.CLOSER, name, null, true, start, end)
                : new Delimiter(Kind.OPENER, name, raw, decodable, start, end);
    }

    /** Reads {@code segment(/segment)?} at {@code pos}; returns the end index or -1. */
    private static int readName(String s, int pos) {
        int end = readSegment(s, pos);
        if (end < 0) {
            return -1;
        }
        if (end < s.length() && s.charAt(end) == '/') {
            int end2 = readSegment(s, end + 1);
            return end2 < 0 ? -1 : end2;
        }
        return end;
    }

    private static int readSegment(String s, int pos) {
        if (pos >= s.length() || !BlockNames.isLower(s.charAt(pos))) {
            return -1;
        }
        int i = pos + 1;
        while (i < s.length() && BlockNames.isNameChar(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Space, tab, line feed, vertical tab, form feed or carriage return. */
    static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    static int skipWs(String s, int pos) {
        while (pos < s.length() && isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }
}
