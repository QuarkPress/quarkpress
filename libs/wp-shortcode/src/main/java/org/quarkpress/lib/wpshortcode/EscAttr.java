package org.quarkpress.lib.wpshortcode;

/**
 * WordPress's esc_attr: every {@code &} is normalized as kses normalizes entities (valid numeric references kept,
 * decimal ones padded to three digits, hexadecimal ones written with a lowercase {@code x} and no leading zeros;
 * known named references kept; anything else becomes {@code &amp;}), then {@code < > " '} are encoded.
 */
final class EscAttr {

    private EscAttr() {}

    static String escape(String text) {
        if (Chars.hasUnpairedSurrogate(text)) {
            return "";
        }
        if (!needsEscaping(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> {
                    i = ampersand(text, i, out);
                    continue;
                }
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                default -> out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static boolean needsEscaping(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                return true;
            }
        }
        return false;
    }

    /** Writes the normalized form of the {@code &} at i (and of the reference it starts); returns where to go on. */
    private static int ampersand(String s, int i, StringBuilder out) {
        int n = s.length();
        int j = i + 1;
        if (j < n && s.charAt(j) == '#') {
            boolean hex = j + 1 < n && (s.charAt(j + 1) == 'x' || s.charAt(j + 1) == 'X');
            int digitsStart = hex ? j + 2 : j + 1;
            int digitsEnd = digitsStart;
            while (digitsEnd < n && (hex ? isHexDigit(s.charAt(digitsEnd)) : isDigit(s.charAt(digitsEnd)))) {
                digitsEnd++;
            }
            if (digitsEnd < n && s.charAt(digitsEnd) == ';') {
                String digits = s.substring(digitsStart, digitsEnd);
                String significant = stripLeadingZeros(digits);
                if (!significant.isEmpty() && significant.length() <= (hex ? 6 : 7)) {
                    int codePoint = Integer.parseInt(significant, hex ? 16 : 10);
                    if (isXmlChar(codePoint)) {
                        out.append(hex ? "&#x" + significant : "&#" + padTo3(significant))
                                .append(';');
                    } else {
                        out.append(hex ? "&amp;#x" : "&amp;#").append(digits).append(';');
                    }
                    return digitsEnd + 1;
                }
            }
        } else {
            int lettersEnd = j;
            while (lettersEnd < n && Chars.isAsciiLetter(s.charAt(lettersEnd))) {
                lettersEnd++;
            }
            int letters = lettersEnd - j;
            if (letters >= 2 && letters <= 8) {
                int digitsEnd = lettersEnd;
                while (digitsEnd < n && isDigit(s.charAt(digitsEnd))) {
                    digitsEnd++;
                }
                if (digitsEnd - lettersEnd <= 2 && digitsEnd < n && s.charAt(digitsEnd) == ';') {
                    String name = s.substring(j, digitsEnd);
                    out.append(HtmlEntityNames.contains(name) ? "&" : "&amp;")
                            .append(name)
                            .append(';');
                    return digitsEnd + 1;
                }
            }
        }
        out.append("&amp;");
        return i + 1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String stripLeadingZeros(String digits) {
        int k = 0;
        while (k < digits.length() && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    private static String padTo3(String digits) {
        return digits.length() >= 3 ? digits : "0".repeat(3 - digits.length()) + digits;
    }

    /** XML's Char production: tab, LF, CR, and U+0020–U+D7FF, U+E000–U+FFFD, U+10000–U+10FFFF. */
    private static boolean isXmlChar(int c) {
        return c == 0x9
                || c == 0xA
                || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD)
                || (c >= 0x10000 && c <= 0x10FFFF);
    }
}
