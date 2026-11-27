package org.quarkword.lib.wpformatting;

/**
 * The spaces texturize looks for around quotes and dashes: carriage return, line feed, tab, space, no-break space,
 * and the entity text {@code &nbsp;} (lower case, with its semicolon).
 */
final class Spaces {

    private static final String NBSP_ENTITY = "&nbsp;";

    private Spaces() {}

    /** The length of the space starting at {@code i}: 1 for a char, 6 for {@code &nbsp;}, else 0. */
    static int lengthAt(CharSequence s, int i) {
        if (i < 0 || i >= s.length()) {
            return 0;
        }
        if (isSpaceChar(s.charAt(i))) {
            return 1;
        }
        return startsWith(s, i, NBSP_ENTITY) ? NBSP_ENTITY.length() : 0;
    }

    /** The length of the space ending just before {@code end}: 1 for a char, 6 for {@code &nbsp;}, else 0. */
    static int lengthEndingAt(CharSequence s, int end) {
        if (end <= 0 || end > s.length()) {
            return 0;
        }
        if (isSpaceChar(s.charAt(end - 1))) {
            return 1;
        }
        int start = end - NBSP_ENTITY.length();
        return start >= 0 && startsWith(s, start, NBSP_ENTITY) ? NBSP_ENTITY.length() : 0;
    }

    private static boolean isSpaceChar(char c) {
        return c == '\r' || c == '\n' || c == '\t' || c == ' ' || c == '\u00a0';
    }

    private static boolean startsWith(CharSequence s, int i, String prefix) {
        if (i + prefix.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            if (s.charAt(i + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }
}
