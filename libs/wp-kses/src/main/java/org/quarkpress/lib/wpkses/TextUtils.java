package org.quarkpress.lib.wpkses;

/** String helpers with the exact character classes of PHP and of PCRE without the UTF flag. */
final class TextUtils {

    static final char REPLACEMENT_CHARACTER = (char) 0xFFFD;

    private TextUtils() {}

    /** What PHP's trim() removes by default: space, tab, line feed, carriage return, NUL and vertical tab. */
    static boolean isPhpTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == 0x0B;
    }

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

    /** PCRE's {@code \s} without the UTF flag, and C's isspace(): space, tab, LF, VT, FF, CR. */
    static boolean isPcreSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** Whitespace for the HTML tokenizer: space, tab, LF, FF, CR (not VT). */
    static boolean isHtmlSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isAsciiAlnum(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c);
    }

    static boolean isHexDigit(char c) {
        return isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** PCRE's {@code \w} without the UTF flag. */
    static boolean isWordChar(char c) {
        return isAsciiAlnum(c) || c == '_';
    }

    static char toAsciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    /** Lowercases ASCII letters only, as PHP's strtolower() does. */
    static String asciiLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    chars[j] = toAsciiLower(chars[j]);
                }
                return new String(chars);
            }
        }
        return s;
    }

    /** True when {@code s} contains {@code part} at {@code offset}, ASCII letters compared without case. */
    static boolean regionMatchesAsciiIgnoreCase(String s, int offset, String part) {
        if (offset < 0 || offset + part.length() > s.length()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (toAsciiLower(s.charAt(offset + i)) != toAsciiLower(part.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** PHP's empty() for a string: true for "" and "0". */
    static boolean phpEmpty(String s) {
        return s.isEmpty() || s.equals("0");
    }

    /** wp_kses_stripslashes: every backslash directly before a double quote is removed. */
    static String stripSlashes(String s) {
        return s.indexOf("\\\"") < 0 ? s : s.replace("\\\"", "\"");
    }

    /**
     * wp_kses_decode_entities: decimal references ending in ';', then hexadecimal ones, become one
     * character whose value is the number modulo 256. Named references are left alone.
     */
    static String decodeNumericBytes(String s) {
        return decodeNumericPass(decodeNumericPass(s, false), true);
    }

    private static String decodeNumericPass(String s, boolean hex) {
        int amp = s.indexOf("&#");
        if (amp < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int copied = 0;
        while (amp >= 0) {
            int digits = amp + 2;
            boolean marker = !hex || (digits < s.length() && (s.charAt(digits) == 'x' || s.charAt(digits) == 'X'));
            if (hex && marker) {
                digits++;
            }
            int end = digits;
            int value = 0;
            while (marker && end < s.length() && (hex ? isHexDigit(s.charAt(end)) : isAsciiDigit(s.charAt(end)))) {
                value = (value * (hex ? 16 : 10) + Character.digit(s.charAt(end), 16)) & 0xFF;
                end++;
            }
            if (marker && end > digits && end < s.length() && s.charAt(end) == ';') {
                out.append(s, copied, amp).append((char) value);
                copied = end + 1;
                amp = s.indexOf("&#", copied);
            } else {
                amp = s.indexOf("&#", amp + 2);
            }
        }
        return out.append(s, copied, s.length()).toString();
    }

    /**
     * wp_kses_html_error: removes a leading run of quoted strings (closed or not) and non-space
     * characters, then the whitespace after it.
     */
    static String htmlError(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int close = s.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
            } else if (!isPcreSpace(c)) {
                i++;
            } else {
                break;
            }
        }
        while (i < n && isPcreSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** Replaces unpaired UTF-16 surrogates with U+FFFD. */
    static String wellFormed(String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return replaceLoneSurrogates(s, i);
            }
        }
        return s;
    }

    private static String replaceLoneSurrogates(String s, int from) {
        StringBuilder out = new StringBuilder(s.length()).append(s, 0, from);
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i));
            } else {
                out.append(Character.isSurrogate(c) ? REPLACEMENT_CHARACTER : c);
            }
        }
        return out.toString();
    }

    /** Length in UTF-8 bytes, which is what PHP's strlen() counts. */
    static long utf8Length(String s) {
        long bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
