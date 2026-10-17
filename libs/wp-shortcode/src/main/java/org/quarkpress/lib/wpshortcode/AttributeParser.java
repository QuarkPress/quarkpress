package org.quarkpress.lib.wpshortcode;

/**
 * Reads shortcode attribute text in one left-to-right pass. At each non-space position the first token form that
 * fits wins: {@code name="value"}, {@code name='value'}, {@code name=value}, {@code "value"}, {@code 'value'},
 * then a bare run of non-space chars. Every form must be followed by one space char, which it consumes, or by
 * the end of the text; a form that is not falls through to the next one.
 */
final class AttributeParser {

    private AttributeParser() {}

    static ShortcodeAttributes parse(String text) {
        if (Chars.hasUnpairedSurrogate(text)) {
            return ShortcodeAttributes.empty();
        }
        String s = collapseInvisibleSpaces(text);
        ShortcodeAttributes.Builder out = new ShortcodeAttributes.Builder();
        int i = 0;
        while (i < s.length()) {
            if (Chars.isSpace(s.charAt(i))) {
                i++;
                continue;
            }
            int next = readNamed(s, i, out);
            if (next < 0) {
                next = readQuoted(s, i, out);
            }
            if (next < 0) {
                next = readBare(s, i, out);
            }
            i = next;
        }
        out.blankUnclosedTags();
        return out.build();
    }

    /** Replaces every run of no-break spaces (U+00A0) and zero-width spaces (U+200B) with one space. */
    static String collapseInvisibleSpaces(String s) {
        int first = 0;
        while (first < s.length() && !isInvisibleSpace(s.charAt(first))) {
            first++;
        }
        if (first == s.length()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        out.append(s, 0, first);
        int i = first;
        while (i < s.length()) {
            if (isInvisibleSpace(s.charAt(i))) {
                out.append(' ');
                while (i < s.length() && isInvisibleSpace(s.charAt(i))) {
                    i++;
                }
            } else {
                out.append(s.charAt(i++));
            }
        }
        return out.toString();
    }

    private static boolean isInvisibleSpace(char c) {
        return c == '\u00A0' || c == '\u200B';
    }

    private static boolean isNameChar(char c) {
        return Chars.isWord(c) || c == '-';
    }

    /** A name, optional spaces, '=', optional spaces and a value; returns the index after it, or -1. */
    private static int readNamed(String s, int i, ShortcodeAttributes.Builder out) {
        int n = s.length();
        int nameEnd = i;
        while (nameEnd < n && isNameChar(s.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == i) {
            return -1;
        }
        int eq = Chars.skipSpaces(s, nameEnd, n);
        if (eq >= n || s.charAt(eq) != '=') {
            return -1;
        }
        int v = Chars.skipSpaces(s, eq + 1, n);
        if (v >= n) {
            return -1;
        }
        char quote = s.charAt(v);
        int valueStart;
        int valueEnd;
        int after;
        if (quote == '"' || quote == '\'') {
            int close = s.indexOf(quote, v + 1);
            if (close < 0) {
                return -1;
            }
            valueStart = v + 1;
            valueEnd = close;
            after = close + 1;
        } else {
            int k = v;
            while (k < n && !Chars.isSpace(s.charAt(k)) && s.charAt(k) != '"' && s.charAt(k) != '\'') {
                k++;
            }
            valueStart = v;
            valueEnd = k;
            after = k;
        }
        int end = endOfToken(s, after);
        if (end < 0) {
            return -1;
        }
        String name = s.substring(i, nameEnd);
        if (!name.equals("0")) {
            out.put(Chars.asciiLower(name), BackslashEscapes.unescape(s.substring(valueStart, valueEnd)));
        }
        return end;
    }

    /** A double- or single-quoted positional; an empty one is dropped. Returns the index after it, or -1. */
    private static int readQuoted(String s, int i, ShortcodeAttributes.Builder out) {
        char quote = s.charAt(i);
        if (quote != '"' && quote != '\'') {
            return -1;
        }
        int close = s.indexOf(quote, i + 1);
        if (close < 0) {
            return -1;
        }
        int end = endOfToken(s, close + 1);
        if (end < 0) {
            return -1;
        }
        if (close > i + 1) {
            out.append(BackslashEscapes.unescape(s.substring(i + 1, close)));
        }
        return end;
    }

    /** A maximal run of non-space chars; always fits at a non-space position. */
    private static int readBare(String s, int i, ShortcodeAttributes.Builder out) {
        int k = i;
        while (k < s.length() && !Chars.isSpace(s.charAt(k))) {
            k++;
        }
        out.append(BackslashEscapes.unescape(s.substring(i, k)));
        return k == s.length() ? k : k + 1;
    }

    /** After a token: the end of the text, or one space char (consumed). Returns the next index, or -1. */
    private static int endOfToken(String s, int at) {
        if (at == s.length()) {
            return at;
        }
        return Chars.isSpace(s.charAt(at)) ? at + 1 : -1;
    }
}
