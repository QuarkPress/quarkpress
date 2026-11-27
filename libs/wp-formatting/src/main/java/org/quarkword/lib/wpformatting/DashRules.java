package org.quarkword.lib.wpformatting;

/** The dash and multiplication rules of one text segment, each a pass over the whole segment. */
final class DashRules {

    private static final String TIMES = "&#215;";

    private DashRules() {}

    /**
     * Dashes in order: {@code ---} is an em dash; {@code --} between spaces (or the segment's ends) is an em dash;
     * any other {@code --} not after {@code xn} is an en dash; a single {@code -} between spaces (or the ends) is
     * an en dash.
     */
    static String dashes(String s, String emDash, String enDash) {
        s = s.replace("---", emDash);
        s = doubleDashes(s, emDash, true);
        s = doubleDashes(s, enDash, false);
        return spacedDash(s, enDash);
    }

    private static String doubleDashes(String s, String dash, boolean spaced) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf("--"); i >= 0; ) {
            boolean match = spaced ? spacedBefore(s, i) && spacedAfter(s, i + 2) : !afterPunycodePrefix(s, i);
            if (match) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(dash);
                copied = i + 2;
                i = s.indexOf("--", copied);
            } else {
                i = s.indexOf("--", i + 1);
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static String spacedDash(String s, String dash) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('-'); i >= 0; i = s.indexOf('-', i + 1)) {
            if (spacedBefore(s, i) && spacedAfter(s, i + 1)) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(dash);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean afterPunycodePrefix(String s, int i) {
        return i >= 2 && s.charAt(i - 2) == 'x' && s.charAt(i - 1) == 'n';
    }

    private static boolean spacedBefore(String s, int i) {
        return i == 0 || Spaces.lengthEndingAt(s, i) > 0;
    }

    /** The end of the segment, a final line feed, or a space. */
    private static boolean spacedAfter(String s, int i) {
        int length = s.length();
        return i >= length || (i == length - 1 && s.charAt(i) == '\n') || Spaces.lengthAt(s, i) > 0;
    }

    /**
     * {@code AxB} between word boundaries, where A and B are a digit followed by digits, dots and commas (A
     * starting with {@code 0} needs at least two such characters), becomes A {@code &#215;} B. Word characters
     * are ASCII letters, digits and the underscore. Nothing changes unless the text holds a digit, {@code x} and
     * a digit in a row somewhere; then every such product changes, including one whose first number ends with a
     * dot or a comma.
     */
    static String multiplication(String s) {
        if (!hasDigitTimesDigit(s)) {
            return s;
        }
        StringBuilder out = null;
        int copied = 0;
        int length = s.length();
        int i = 0;
        while (i < length) {
            if (!Chars.isAsciiDigit(s.charAt(i)) || (i > 0 && Chars.isAsciiWord(s.charAt(i - 1)))) {
                i++;
                continue;
            }
            int runEnd = i + 1;
            while (runEnd < length && QuoteRules.isNumberChar(s.charAt(runEnd))) {
                runEnd++;
            }
            int start = runEnd < length && s.charAt(runEnd) == 'x' ? firstStart(s, i, runEnd) : -1;
            int end = start >= 0 && QuoteRules.digitAt(s, runEnd + 1) ? secondEnd(s, runEnd + 1) : -1;
            if (end < 0) {
                i = runEnd;
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, runEnd).append(TIMES).append(s, runEnd + 1, end);
            copied = end;
            i = end;
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean hasDigitTimesDigit(String s) {
        for (int i = s.indexOf('x'); i >= 0; i = s.indexOf('x', i + 1)) {
            if (QuoteRules.digitAt(s, i - 1) && QuoteRules.digitAt(s, i + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The leftmost start of a first number in the run {@code [from, x)}: a digit at a word boundary, and a
     * {@code 0} only with at least one more character before the {@code x}. -1 if there is none.
     */
    private static int firstStart(String s, int from, int x) {
        for (int j = from; j < x; j++) {
            boolean boundary = j == from || !Chars.isAsciiWord(s.charAt(j - 1));
            if (Chars.isAsciiDigit(s.charAt(j)) && boundary && (s.charAt(j) != '0' || x - j >= 2)) {
                return j;
            }
        }
        return -1;
    }

    /**
     * The end of the longest second number starting at {@code from} (a digit) that ends at a word boundary, or
     * -1.
     */
    private static int secondEnd(String s, int from) {
        int length = s.length();
        int runEnd = from + 1;
        while (runEnd < length && QuoteRules.isNumberChar(s.charAt(runEnd))) {
            runEnd++;
        }
        for (int p = runEnd; p > from; p--) {
            boolean before = Chars.isAsciiWord(s.charAt(p - 1));
            boolean after = p < length && Chars.isAsciiWord(s.charAt(p));
            if (before != after) {
                return p;
            }
        }
        return -1;
    }
}
