package org.quarkword.lib.wpformatting;

import java.util.HashSet;
import java.util.Set;

/**
 * Splits text for texturize into text segments and delimiters. A delimiter is a comment ({@code <!--} through the
 * first {@code -->} after it, or to the end), any other {@code <} through the next {@code >} (or to the end), or,
 * when the text names registered shortcodes, a shortcode tag of one of those names. No text segment contains
 * {@code <}.
 */
final class TexturizeSplitter {

    enum Kind {
        TEXT,
        COMMENT,
        ELEMENT,
        SHORTCODE
    }

    /** Receives each non-empty segment as a range of the text. */
    @FunctionalInterface
    interface SegmentVisitor {
        void segment(int start, int end, Kind kind);
    }

    private static final String COMMENT_OPEN = "<!--";
    private static final String COMMENT_CLOSE = "-->";

    private TexturizeSplitter() {}

    /**
     * The registered names the text uses as shortcodes: each run of name characters right after {@code [} or
     * {@code [/}, kept when it is registered.
     */
    static Set<String> usedShortcodes(String text, Set<String> registered) {
        Set<String> used = new HashSet<>();
        if (registered.isEmpty()) {
            return used;
        }
        int length = text.length();
        for (int open = text.indexOf('['); open >= 0; open = text.indexOf('[', open + 1)) {
            int start = open + 1;
            if (start < length && text.charAt(start) == '/') {
                start++;
            }
            int end = nameEnd(text, start);
            if (end > start) {
                String name = text.substring(start, end);
                if (registered.contains(name)) {
                    used.add(name);
                }
            }
        }
        return used;
    }

    /** Calls {@code visitor} for each non-empty segment in order. */
    static void forEachSegment(String text, Set<String> shortcodes, SegmentVisitor visitor) {
        int length = text.length();
        Forward lessThan = new Forward(text, "<");
        Forward bracket = new Forward(text, "[");
        Forward greaterThan = new Forward(text, ">");
        Forward commentClose = new Forward(text, COMMENT_CLOSE);
        boolean withShortcodes = !shortcodes.isEmpty();
        int textStart = 0;
        int bracketFrom = 0;
        while (true) {
            int tag = lessThan.from(textStart);
            int code = withShortcodes ? bracket.from(Math.max(bracketFrom, textStart)) : -1;
            int start;
            int end;
            Kind kind;
            if (code >= 0 && (tag < 0 || code < tag)) {
                end = shortcodeEnd(text, code, shortcodes);
                if (end < 0) {
                    bracketFrom = code + 1;
                    continue;
                }
                start = code;
                kind = Kind.SHORTCODE;
            } else if (tag >= 0) {
                start = tag;
                if (text.startsWith(COMMENT_OPEN, tag)) {
                    int close = commentClose.from(tag + 2);
                    end = close < 0 ? length : close + COMMENT_CLOSE.length();
                    kind = Kind.COMMENT;
                } else {
                    int close = greaterThan.from(tag + 1);
                    end = close < 0 ? length : close + 1;
                    kind = Kind.ELEMENT;
                }
            } else {
                if (textStart < length) {
                    visitor.segment(textStart, length, Kind.TEXT);
                }
                return;
            }
            if (textStart < start) {
                visitor.segment(textStart, start, Kind.TEXT);
            }
            visitor.segment(start, end, kind);
            textStart = end;
        }
    }

    /**
     * The end of the shortcode tag starting at {@code open}, or -1: {@code [}, optionally {@code /} or {@code [},
     * a used name followed by whitespace, {@code ]} or {@code /}, then characters other than brackets and angle
     * brackets mixed with complete tags whose insides hold no brackets, then {@code ]} and an optional second
     * {@code ]}. Nothing is given back once taken, so a {@code ]} inside an attribute ends the tag.
     */
    static int shortcodeEnd(String text, int open, Set<String> shortcodes) {
        int length = text.length();
        int start = open + 1;
        if (start < length && (text.charAt(start) == '/' || text.charAt(start) == '[')) {
            start++;
        }
        int nameEnd = nameEnd(text, start);
        if (nameEnd == start || nameEnd >= length) {
            return -1;
        }
        char after = text.charAt(nameEnd);
        if (!(Chars.isPcreSpace(after) || after == ']' || after == '/')
                || !shortcodes.contains(text.substring(start, nameEnd))) {
            return -1;
        }
        int i = nameEnd;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '<') {
                int j = i + 1;
                while (j < length && !isTagStop(text.charAt(j))) {
                    j++;
                }
                if (j < length && text.charAt(j) == '>') {
                    i = j + 1;
                    continue;
                }
                break;
            }
            if (c == '[' || c == ']' || c == '>') {
                break;
            }
            i++;
        }
        if (i >= length || text.charAt(i) != ']') {
            return -1;
        }
        i++;
        return i < length && text.charAt(i) == ']' ? i + 1 : i;
    }

    private static boolean isTagStop(char c) {
        return c == '[' || c == ']' || c == '>';
    }

    /** The end of the run of shortcode-name characters starting at {@code i}. */
    private static int nameEnd(String text, int i) {
        int length = text.length();
        while (i < length && isNameChar(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNameChar(char c) {
        return c > ' ' && c != '<' && c != '>' && c != '&' && c != '/' && c != '[' && c != ']' && c != '=';
    }
}
