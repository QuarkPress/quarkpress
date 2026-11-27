package org.quarkword.lib.wpformatting;

import org.quarkword.lib.wpkses.Kses;

/**
 * {@code wp_html_excerpt}: tags stripped and whitespace runs collapsed, cut to a number of code points, a partial
 * character reference removed from the end, and the result trimmed with {@code more} appended when it is shorter
 * than the stripped text.
 */
final class HtmlExcerpt {

    /** The most UTF-8 bytes after a final {@code &} that still make it a partial reference. */
    private static final int PARTIAL_REFERENCE_BYTES = 6;

    private HtmlExcerpt() {}

    static String excerpt(String text, int count, String more) {
        String stripped = Kses.stripAllTags(text, true);
        String cut = removePartialReference(stripped.substring(0, cutEnd(stripped, count)));
        return cut.equals(stripped) ? cut : Formatting.phpTrim(cut) + more;
    }

    /**
     * The end of the first {@code count} code points; a negative count keeps all but the last {@code -count}, and
     * nothing when that is the whole text.
     */
    private static int cutEnd(String text, int count) {
        int total = text.codePointCount(0, text.length());
        long keep = count >= 0 ? Math.min(count, total) : Math.max(0, total + (long) count);
        return text.offsetByCodePoints(0, (int) keep);
    }

    /**
     * Removes a final {@code &} followed by at most six UTF-8 bytes, none of them {@code ;} or a PCRE space, together
     * with those bytes; of several such {@code &} the leftmost is taken.
     */
    private static String removePartialReference(String text) {
        int bytesAfter = 0;
        int from = -1;
        int i = text.length();
        while (i > 0) {
            int cp = text.codePointBefore(i);
            if (cp == ';' || (cp < 0x80 && Chars.isPcreSpace((char) cp))) {
                break;
            }
            int at = i - Character.charCount(cp);
            if (cp == '&') {
                from = at;
            }
            bytesAfter += utf8Length(cp);
            if (bytesAfter > PARTIAL_REFERENCE_BYTES) {
                break;
            }
            i = at;
        }
        return from < 0 ? text : text.substring(0, from);
    }

    private static int utf8Length(int cp) {
        if (cp < 0x80) {
            return 1;
        }
        if (cp < 0x800) {
            return 2;
        }
        return cp < 0x10000 ? 3 : 4;
    }
}
