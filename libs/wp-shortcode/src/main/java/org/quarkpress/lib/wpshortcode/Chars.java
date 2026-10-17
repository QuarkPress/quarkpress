package org.quarkpress.lib.wpshortcode;

/**
 * Character classes of the shortcode grammar. WordPress matches bytes; this library matches UTF-16 chars and
 * treats every non-ASCII char (each half of a surrogate pair included) as an ordinary name char that is neither
 * a word char nor a space, which gives the same results as byte matching on valid UTF-8.
 */
final class Chars {

    private Chars() {}

    /** ASCII letter, digit or underscore. */
    static boolean isWord(char c) {
        return isAsciiAlphanumeric(c) || c == '_';
    }

    static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isAsciiAlphanumeric(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    /** Space, tab, line feed, vertical tab, form feed or carriage return. */
    static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** True for chars that end a candidate tag name and that a registered name may not contain. */
    static boolean endsName(char c) {
        return c <= ' ' || c == '<' || c == '>' || c == '&' || c == '/' || c == '[' || c == ']' || c == '=';
    }

    /** The first index in {@code [from, to)} that is not a space, or {@code to}. */
    static int skipSpaces(String s, int from, int to) {
        int i = from;
        while (i < to && isSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index just after the last non-space char in {@code [from, to)}, or {@code from}. */
    static int trimEnd(String s, int from, int to) {
        int i = to;
        while (i > from && isSpace(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** Lowercases ASCII letters only, as PHP 8's strtolower does. */
    static String asciiLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    if (chars[j] >= 'A' && chars[j] <= 'Z') {
                        chars[j] += 'a' - 'A';
                    }
                }
                return new String(chars);
            }
        }
        return s;
    }

    /** True when the text holds a surrogate without its partner: the UTF-16 form of invalid UTF-8. */
    static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                } else {
                    return true;
                }
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** PHP's default trim set: space, tab, line feed, carriage return, NUL and vertical tab (no form feed). */
    static boolean isPhpTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    /** True when trimming PHP's default set leaves nothing. */
    static boolean isPhpBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isPhpTrimChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Trims PHP's default set from both ends. */
    static String phpTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPhpTrimChar(s.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimChar(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Drops U+0000-U+0008, U+000B, U+000C and U+000E-U+001F, the controls kses removes. */
    static String removeControls(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean control = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
            if (control && out == null) {
                out = new StringBuilder(s.length());
                out.append(s, 0, i);
            } else if (!control && out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    /** True when {@code s} has {@code lowercasePrefix} at {@code at}, comparing ASCII letters without case. */
    static boolean startsWithIgnoreAsciiCase(String s, int at, String lowercasePrefix) {
        if (at < 0 || at + lowercasePrefix.length() > s.length()) {
            return false;
        }
        for (int i = 0; i < lowercasePrefix.length(); i++) {
            char c = s.charAt(at + i);
            if (c >= 'A' && c <= 'Z') {
                c += 'a' - 'A';
            }
            if (c != lowercasePrefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
