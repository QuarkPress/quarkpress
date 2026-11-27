package org.quarkword.lib.wpformatting;

/**
 * The quote rules of one text segment, each a pass over the whole segment. Decisions that are rendered late are
 * written as sentinels that contain {@code <}, which no text segment holds, so typed text can never look like
 * one.
 */
final class QuoteRules {

    /** An apostrophe decided early; rendered after the primes. */
    static final String APOSTROPHE = "<\u0001apos>";
    /** An opening single quote decided early; the primes split on it. */
    static final String OPENING_SINGLE = "<\u0001osq>";
    /** An opening double quote decided early; the primes split on it. */
    static final String OPENING_DOUBLE = "<\u0001oq>";

    private static final String BOUNDARY_PUNCTUATION = ".,:;!?)}-]";
    private static final String SINGLE_OPENERS = "([{\"-";
    private static final String DOUBLE_OPENERS = "([{-";
    private static final String APOSTROPHE_STOPS = ".,:;!?\"'(){}[]-";
    private static final String LT = "&lt;";
    private static final String GT = "&gt;";

    private QuoteRules() {}

    /** The single-quote rules in order; the text holds a {@code '}. */
    static String singles(String s, TexturizeStrings strings) {
        s = yearInQuotes(s, '\'', strings.closingSingleQuote());
        s = yearInQuotes(s, '"', strings.closingDoubleQuote());
        s = yearApostrophe(s);
        s = quotedNumber(s, '\'', OPENING_SINGLE, strings.closingSingleQuote());
        s = openingSingle(s);
        return inWordApostrophe(s);
    }

    /** The double-quote rules in order; the text holds a {@code "}. */
    static String doubles(String s, TexturizeStrings strings) {
        s = quotedNumber(s, '"', OPENING_DOUBLE, strings.closingDoubleQuote());
        return openingDouble(s);
    }

    /**
     * True at the end of the text, before a final line feed, before {@code . , : ; ! ? ) } - ]}, before
     * {@code &gt;} and before a space.
     */
    static boolean boundaryAfter(String s, int i) {
        int length = s.length();
        if (i >= length || (i == length - 1 && s.charAt(i) == '\n')) {
            return true;
        }
        return BOUNDARY_PUNCTUATION.indexOf(s.charAt(i)) >= 0 || s.startsWith(GT, i) || Spaces.lengthAt(s, i) > 0;
    }

    /** {@code '} + two digits + {@code close} before a boundary: apostrophe, the digits, the closing quote. */
    private static String yearInQuotes(String s, char close, String closeText) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('\''); i >= 0; ) {
            if (digitAt(s, i + 1) && digitAt(s, i + 2) && charAt(s, i + 3) == close && boundaryAfter(s, i + 4)) {
                out = Edits.started(out, s);
                out.append(s, copied, i)
                        .append(APOSTROPHE)
                        .append(s, i + 1, i + 3)
                        .append(closeText);
                copied = i + 4;
                i = s.indexOf('\'', copied);
            } else {
                i = s.indexOf('\'', i + 1);
            }
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * {@code '} + two digits, unless a digit or {@code %} follows them, or {@code .} or {@code ,} and a digit:
     * the {@code '} is an apostrophe.
     */
    private static String yearApostrophe(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('\''); i >= 0; i = s.indexOf('\'', i + 1)) {
            if (digitAt(s, i + 1) && digitAt(s, i + 2) && !continuesNumber(s, i + 3)) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(APOSTROPHE);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean continuesNumber(String s, int i) {
        char c = charAt(s, i);
        return c == '%' || Chars.isAsciiDigit(c) || ((c == '.' || c == ',') && digitAt(s, i + 1));
    }

    /**
     * At the start or after a space: {@code quote}, a digit, digits, dots and commas, {@code quote}. It becomes
     * the opening sentinel, the number and the closing quote.
     */
    private static String quotedNumber(String s, char quote, String opening, String closeText) {
        StringBuilder out = null;
        int copied = 0;
        int length = s.length();
        for (int i = s.indexOf(quote); i >= 0; ) {
            if ((i == 0 || Spaces.lengthEndingAt(s, i) > 0) && digitAt(s, i + 1)) {
                int end = i + 2;
                while (end < length && isNumberChar(s.charAt(end))) {
                    end++;
                }
                if (end < length && s.charAt(end) == quote) {
                    out = Edits.started(out, s);
                    out.append(s, copied, i)
                            .append(opening)
                            .append(s, i + 1, end)
                            .append(closeText);
                    copied = end + 1;
                    i = s.indexOf(quote, copied);
                    continue;
                }
            }
            i = s.indexOf(quote, i + 1);
        }
        return Edits.finished(out, s, copied);
    }

    /** A {@code '} at the start or after {@code ( [ { " -}, {@code &lt;} or a space opens a quote. */
    private static String openingSingle(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('\''); i >= 0; i = s.indexOf('\'', i + 1)) {
            if (afterOpener(s, i, SINGLE_OPENERS)) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(OPENING_SINGLE);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * A {@code '} not after a space, and not before the end, punctuation, a quote, a bracket, a dash,
     * {@code &lt;}, {@code &gt;} or a space, is an apostrophe.
     */
    private static String inWordApostrophe(String s) {
        StringBuilder out = null;
        int copied = 0;
        int length = s.length();
        for (int i = s.indexOf('\''); i >= 0; i = s.indexOf('\'', i + 1)) {
            int next = i + 1;
            boolean stops = next >= length
                    || APOSTROPHE_STOPS.indexOf(s.charAt(next)) >= 0
                    || s.startsWith(LT, next)
                    || s.startsWith(GT, next)
                    || Spaces.lengthAt(s, next) > 0;
            if (Spaces.lengthEndingAt(s, i) == 0 && !stops) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(APOSTROPHE);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /** A {@code "} at the start or after {@code ( [ { -}, {@code &lt;} or a space, not before a space, opens. */
    private static String openingDouble(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('"'); i >= 0; i = s.indexOf('"', i + 1)) {
            if (afterOpener(s, i, DOUBLE_OPENERS) && Spaces.lengthAt(s, i + 1) == 0) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(OPENING_DOUBLE);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean afterOpener(String s, int i, String openers) {
        return i == 0
                || openers.indexOf(s.charAt(i - 1)) >= 0
                || (i >= LT.length() && s.startsWith(LT, i - LT.length()))
                || Spaces.lengthEndingAt(s, i) > 0;
    }

    static boolean isNumberChar(char c) {
        return Chars.isAsciiDigit(c) || c == '.' || c == ',';
    }

    static boolean digitAt(String s, int i) {
        return i >= 0 && i < s.length() && Chars.isAsciiDigit(s.charAt(i));
    }

    /** The char at {@code i}, or NUL past the end (callers never compare with NUL). */
    private static char charAt(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }
}
