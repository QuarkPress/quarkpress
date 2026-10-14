package org.quarkpress.lib.wpblocks;

/** Recognises the three block delimiter shapes at a given index. Anything else is text. */
final class DelimiterScanner {

    static final String COMMENT_OPEN = "<!--";
    private static final String COMMENT_CLOSE = "-->";
    private static final String VOID_CLOSE = "/-->";
    private static final String WP = "wp:";
    private static final String WP_CLOSER = "/wp:";

    enum Kind {
        OPENER,
        VOID,
        CLOSER
    }

    /** A recognised delimiter occupying {@code [start, end)} of the input. */
    record Delimiter(Kind kind, String name, String rawAttributes, int start, int end) {}

    private DelimiterScanner() {}

    /** Returns the delimiter starting exactly at {@code start}, or null if there is none. */
    static Delimiter tryRead(String s, int start) {
        if (!s.startsWith(COMMENT_OPEN, start)) {
            return null;
        }
        int pos = skipWs(s, start + COMMENT_OPEN.length());
        if (pos == start + COMMENT_OPEN.length()) {
            return null; // whitespace after <!-- is mandatory
        }
        boolean closer;
        if (s.startsWith(WP_CLOSER, pos)) {
            closer = true;
            pos += WP_CLOSER.length();
        } else if (s.startsWith(WP, pos)) {
            closer = false;
            pos += WP.length();
        } else {
            return null;
        }
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

        if (closer) {
            return s.startsWith(COMMENT_CLOSE, pos)
                    ? new Delimiter(Kind.CLOSER, name, null, start, pos + COMMENT_CLOSE.length())
                    : null;
        }

        String raw = null;
        if (pos < s.length() && s.charAt(pos) == '{') {
            int attrEnd = AttributeJson.findEnd(s, pos);
            if (attrEnd < 0) {
                return null;
            }
            raw = s.substring(pos, attrEnd);
            int afterAttrs = skipWs(s, attrEnd);
            if (afterAttrs == attrEnd) {
                return null; // whitespace after the attributes is mandatory
            }
            pos = afterAttrs;
        }
        if (s.startsWith(VOID_CLOSE, pos)) {
            return new Delimiter(Kind.VOID, name, raw, start, pos + VOID_CLOSE.length());
        }
        if (s.startsWith(COMMENT_CLOSE, pos)) {
            return new Delimiter(Kind.OPENER, name, raw, start, pos + COMMENT_CLOSE.length());
        }
        return null;
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

    static int skipWs(String s, int pos) {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            pos++;
        }
        return pos;
    }
}
