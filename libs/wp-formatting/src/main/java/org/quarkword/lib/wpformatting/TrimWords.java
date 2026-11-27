package org.quarkword.lib.wpformatting;

import org.quarkword.lib.wpkses.Kses;

/**
 * {@code wp_trim_words} on text: tags are stripped and the ends trimmed, then the text is cut to a number of words or
 * of code points. The limit follows PHP: the text is split into at most {@code numWords + 1} pieces (computed without
 * overflow), and when there are more pieces than {@code numWords} the last is dropped and {@code more} appended.
 */
final class TrimWords {

    private TrimWords() {}

    static String apply(String text, int numWords, String more, boolean characters) {
        String stripped = Kses.stripAllTags(text, false);
        return characters ? characters(stripped, numWords, more) : words(stripped, numWords, more);
    }

    /** A word separator: CR, LF, tab or space, nothing else. */
    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * Word mode. PHP's split treats a limit of 0 or -1 as no limit, and any other limit below 2 as "the whole text is
     * one piece"; empty pieces are dropped.
     */
    private static String words(String text, int numWords, String more) {
        long limit = (long) numWords + 1;
        if (limit == 0 || limit == -1) {
            return allButLastWord(text) + more;
        }
        if (limit < 2) {
            // one piece, or none for empty text; it is dropped unless there are no pieces and numWords is 0
            return numWords == 0 && text.isEmpty() ? "" : more;
        }
        StringBuilder out = new StringBuilder();
        int found = 0;
        int i = skipSeparators(text, 0);
        while (i < text.length()) {
            if (found == numWords) {
                // a further piece exists, so the text has more words than numWords
                return out.append(more).toString();
            }
            int end = wordEnd(text, i);
            if (found > 0) {
                out.append(' ');
            }
            out.append(text, i, end);
            found++;
            i = skipSeparators(text, end);
        }
        return out.toString();
    }

    /** Every word but the last, joined by single spaces. */
    private static String allButLastWord(String text) {
        StringBuilder out = new StringBuilder();
        int previousStart = -1;
        int previousEnd = -1;
        int i = skipSeparators(text, 0);
        while (i < text.length()) {
            int end = wordEnd(text, i);
            if (previousStart >= 0) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(text, previousStart, previousEnd);
            }
            previousStart = i;
            previousEnd = end;
            i = skipSeparators(text, end);
        }
        return out.toString();
    }

    private static int skipSeparators(String text, int i) {
        while (i < text.length() && isSeparator(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int wordEnd(String text, int i) {
        while (i < text.length() && !isSeparator(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Character mode. Runs of separators become one space and spaces are trimmed from the ends; then the first
     * {@code numWords + 1} code points are kept, a negative count dropping that many from the end instead.
     */
    private static String characters(String text, int numWords, String more) {
        String collapsed = collapse(text);
        int total = collapsed.codePointCount(0, collapsed.length());
        long take = (long) numWords + 1;
        long kept = take >= 0 ? Math.min(take, total) : Math.max(0, total + take);
        if (kept > numWords) {
            int keep = (int) Math.max(0, kept - 1);
            return collapsed.substring(0, collapsed.offsetByCodePoints(0, keep)) + more;
        }
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, (int) kept));
    }

    /** Each run of CR, LF, tab and space as one space, with spaces trimmed from both ends. */
    private static String collapse(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isSeparator(c)) {
                out.append(' ');
                i = skipSeparators(text, i);
            } else {
                out.append(c);
                i++;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == ' ') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(start, end);
    }
}
