package org.quarkword.lib.wpstyleengine;

/**
 * WordPress's {@code _wp_to_kebab_case}, the lodash {@code kebabCase} rules, as one forward scan over code points.
 *
 * <p>ASCII apostrophes are deleted first. Each code point then has one class: upper (Latin-1 capitals), lower
 * (Latin-1 small letters), digit (any Unicode 17.0 decimal digit), break (ASCII and Latin-1 punctuation, symbols and
 * controls, general punctuation and the Unicode spaces) or misc (everything else, which counts as both upper-like
 * and lower-like). Words are found left to right, trying in order:
 *
 * <ol>
 *   <li>an optional upper and one or more lower, followed by a break, an upper or the end;
 *   <li>the longest run of upper-like characters followed by a break, the end, or an upper and a lower-like;
 *   <li>an optional upper and one or more lower-like;
 *   <li>one or more upper;
 *   <li>an ordinal ({@code 1ST}, {@code 22nd}, {@code 4th}, ...) followed by a word boundary, {@code _} or a letter of
 *       the other ASCII case;
 *   <li>one or more digits.
 * </ol>
 *
 * Breaks start no word and are skipped. The words are joined with {@code -} and ASCII letters lowercased.
 *
 * <p>The two Unicode-dependent classes, decimal digits and the word characters of the ordinal's word boundary, come
 * from the fixed Unicode 17.0 tables in {@link UnicodeTables} (the oracle php's PCRE2 10.49), never from the JDK, so
 * the output is the same on every JDK. Text with an unpaired surrogate is not valid UTF-8, and WordPress's {@code /u}
 * pattern then matches nothing, so the result is {@code ""}.
 */
final class KebabCase {

    private static final byte UPPER = 0;
    private static final byte LOWER = 1;
    private static final byte DIGIT = 2;
    private static final byte BREAK = 3;
    private static final byte MISC = 4;

    private KebabCase() {}

    static String of(String text) {
        int[] cps = text.codePoints().filter(cp -> cp != '\'').toArray();
        for (int cp : cps) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                return "";
            }
        }
        int n = cps.length;
        byte[] cls = new byte[n];
        for (int i = 0; i < n; i++) {
            cls[i] = classOf(cps[i]);
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int p = 0;
        while (p < n) {
            if (cls[p] == BREAK) {
                p++;
                continue;
            }
            int end = word(cps, cls, p);
            if (!out.isEmpty()) {
                out.append('-');
            }
            for (int i = p; i < end; i++) {
                int cp = cps[i];
                out.appendCodePoint(cp >= 'A' && cp <= 'Z' ? cp + ('a' - 'A') : cp);
            }
            p = end;
        }
        return out.toString();
    }

    /** The end of the word that starts at {@code p}, which is not a break. */
    private static int word(int[] cps, byte[] cls, int p) {
        int n = cls.length;
        byte first = cls[p];
        if (first == DIGIT) {
            int q = p;
            while (q < n && cls[q] == DIGIT) {
                q++;
            }
            int ordinal = ordinalEnd(cps, cls, q);
            return ordinal >= 0 ? ordinal : q;
        }
        // 1: optional upper, lower run, then a break, an upper or the end
        int s = first == UPPER ? p + 1 : p;
        int q = s;
        while (q < n && cls[q] == LOWER) {
            q++;
        }
        if (q > s && (q == n || cls[q] == BREAK || cls[q] == UPPER)) {
            return q;
        }
        // 2: the longest upper-like run whose end is a break, the end, or an upper followed by a lower-like
        if (first == UPPER || first == MISC) {
            int r = p;
            int good = -1;
            while (r < n && isUpperLike(cls[r])) {
                if (r > p && cls[r] == UPPER && r + 1 < n && isLowerLike(cls[r + 1])) {
                    good = r;
                }
                r++;
            }
            if (r == n || cls[r] == BREAK) {
                return r;
            }
            if (good > p) {
                return good;
            }
        }
        // 3: optional upper, lower-like run
        s = first == UPPER ? p + 1 : p;
        q = s;
        while (q < n && isLowerLike(cls[q])) {
            q++;
        }
        if (q > s) {
            return q;
        }
        // 4: an upper run (only an upper is left here)
        q = p;
        while (q < n && cls[q] == UPPER) {
            q++;
        }
        return q;
    }

    /**
     * The end of an ordinal whose digits end at {@code q}: the last digit with its suffix ({@code 1st}, {@code 2nd},
     * {@code 3rd}, or {@code th} after any other digit, all in one ASCII case), followed by a word boundary,
     * {@code _} or an ASCII letter of the other case; -1 when there is none.
     */
    private static int ordinalEnd(int[] cps, byte[] cls, int q) {
        int n = cps.length;
        if (q + 2 > n) {
            return -1;
        }
        int digit = cps[q - 1];
        int a = cps[q];
        int b = cps[q + 1];
        boolean lower = suffix(digit, a, b, 's', 't', 'n', 'd', 'r', 'h');
        boolean upper = suffix(digit, a, b, 'S', 'T', 'N', 'D', 'R', 'H');
        if (!lower && !upper) {
            return -1;
        }
        int end = q + 2;
        if (end == n || !isWordChar(cps[end])) {
            return end;
        }
        int next = cps[end];
        if (next == '_') {
            return end;
        }
        boolean otherCase = upper ? next >= 'a' && next <= 'z' : next >= 'A' && next <= 'Z';
        return otherCase ? end : -1;
    }

    /** Whether {@code digit a b} is an ordinal in the case whose letters are given. */
    private static boolean suffix(int digit, int a, int b, char s, char t, char nn, char d, char r, char h) {
        return switch (digit) {
            case '1' -> a == s && b == t;
            case '2' -> a == nn && b == d;
            case '3' -> a == r && b == d;
            default -> a == t && b == h;
        };
    }

    private static boolean isUpperLike(byte c) {
        return c == UPPER || c == MISC;
    }

    private static boolean isLowerLike(byte c) {
        return c == LOWER || c == MISC;
    }

    /**
     * A word character for a Unicode word boundary: a letter, a number, a non-spacing mark or a connector (Unicode
     * 17.0).
     */
    private static boolean isWordChar(int cp) {
        return UnicodeTables.contains(UnicodeTables.WORD, cp);
    }

    private static byte classOf(int cp) {
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 0xC0 && cp <= 0xD6) || (cp >= 0xD8 && cp <= 0xDE)) {
            return UPPER;
        }
        if ((cp >= 'a' && cp <= 'z') || (cp >= 0xDF && cp <= 0xF6) || (cp >= 0xF8 && cp <= 0xFF)) {
            return LOWER;
        }
        if (cp <= 0x2F
                || (cp >= 0x3A && cp <= 0x40)
                || (cp >= 0x5B && cp <= 0x60)
                || (cp >= 0x7B && cp <= 0xBF)
                || (cp >= 0x2000 && cp <= 0x206F)
                || cp == 0x1680
                || cp == 0x180E
                || cp == 0x3000
                || cp == 0xFEFF) {
            return BREAK;
        }
        if (UnicodeTables.contains(UnicodeTables.DIGIT, cp)) {
            return DIGIT;
        }
        return MISC;
    }
}
