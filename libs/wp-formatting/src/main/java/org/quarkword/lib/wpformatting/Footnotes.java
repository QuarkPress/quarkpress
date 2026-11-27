package org.quarkword.lib.wpformatting;

/**
 * {@code excerpt_remove_footnotes}: removes every footnote marker of the block editor's exact shape,
 * {@code <sup data-fn="…" class="…">}, optional PCRE spaces, {@code <a href="…" id="…">}, ASCII digits,
 * {@code </a>}, optional PCRE spaces, {@code </sup>}. Each attribute value is one or more chars other than a double
 * quote; names, order, quotes and case are fixed. Markers are found left to right and never overlap.
 */
final class Footnotes {

    private static final String START = "<sup data-fn=\"";
    private static final String CLASS = "\" class=\"";
    private static final String TAG_END = "\">";
    private static final String LINK = "<a href=\"";
    private static final String ID = "\" id=\"";
    private static final String LINK_END = "</a>";
    private static final String END = "</sup>";

    private Footnotes() {}

    static String remove(String content) {
        if (!content.contains("data-fn=")) {
            return content;
        }
        Forward starts = new Forward(content, START);
        StringBuilder out = null;
        int copied = 0;
        int at = starts.from(0);
        while (at >= 0) {
            int end = markerEnd(content, at);
            if (end < 0) {
                at = starts.from(at + 1);
                continue;
            }
            if (out == null) {
                out = new StringBuilder(content.length());
            }
            out.append(content, copied, at);
            copied = end;
            at = starts.from(end);
        }
        if (out == null) {
            return content;
        }
        return out.append(content, copied, content.length()).toString();
    }

    /** The end of the marker that starts at {@code at}, or -1 when the text there is not one. */
    private static int markerEnd(String s, int at) {
        int i = value(s, at + START.length());
        i = literal(s, i, CLASS);
        i = value(s, i);
        i = literal(s, i, TAG_END);
        i = spaces(s, i);
        i = literal(s, i, LINK);
        i = value(s, i);
        i = literal(s, i, ID);
        i = value(s, i);
        i = literal(s, i, TAG_END);
        i = digits(s, i);
        i = literal(s, i, LINK_END);
        i = spaces(s, i);
        return literal(s, i, END);
    }

    /** After one or more chars other than {@code "}: the index of the closing quote, or -1. */
    private static int value(String s, int i) {
        if (i < 0) {
            return -1;
        }
        int quote = s.indexOf('"', i);
        return quote > i ? quote : -1;
    }

    private static int literal(String s, int i, String text) {
        return i >= 0 && s.startsWith(text, i) ? i + text.length() : -1;
    }

    private static int spaces(String s, int i) {
        if (i < 0) {
            return -1;
        }
        while (i < s.length() && Chars.isPcreSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** After one or more ASCII digits, or -1. */
    private static int digits(String s, int i) {
        if (i < 0) {
            return -1;
        }
        int start = i;
        while (i < s.length() && Chars.isAsciiDigit(s.charAt(i))) {
            i++;
        }
        return i > start ? i : -1;
    }
}
