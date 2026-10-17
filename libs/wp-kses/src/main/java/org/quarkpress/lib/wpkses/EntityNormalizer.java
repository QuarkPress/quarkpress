package org.quarkpress.lib.wpkses;

/**
 * wp_kses_normalize_entities in one linear pass: every '&' is escaped unless it starts a valid
 * numeric reference or an allowed named reference that ends in ';'.
 */
final class EntityNormalizer {

    private EntityNormalizer() {}

    /** Whether the code point is a valid XML character. */
    static boolean isValidCodePoint(int cp) {
        return cp == 0x9
                || cp == 0xA
                || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    /** {@code xml} selects the 'xml' context, otherwise the 'html' one. */
    static String normalize(String s, boolean xml) {
        int amp = s.indexOf('&');
        if (amp < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int copied = 0;
        while (amp >= 0) {
            out.append(s, copied, amp);
            copied = appendNormalized(s, amp, xml, out);
            amp = s.indexOf('&', copied);
        }
        return out.append(s, copied, s.length()).toString();
    }

    /** Writes the normalized text for the '&' at {@code amp}; returns where copying continues. */
    private static int appendNormalized(String s, int amp, boolean xml, StringBuilder out) {
        int n = s.length();
        int p = amp + 1;
        if (p < n && s.charAt(p) == '#') {
            boolean hex = p + 1 < n && (s.charAt(p + 1) == 'x' || s.charAt(p + 1) == 'X');
            int digits = hex ? p + 2 : p + 1;
            int end = digits;
            while (end < n && (hex ? TextUtils.isHexDigit(s.charAt(end)) : TextUtils.isAsciiDigit(s.charAt(end)))) {
                end++;
            }
            if (end > digits && end < n && s.charAt(end) == ';') {
                int significant = digits;
                while (significant < end && s.charAt(significant) == '0') {
                    significant++;
                }
                int length = end - significant;
                if (length >= 1 && length <= (hex ? 6 : 7)) {
                    int value = Integer.parseInt(s, significant, end, hex ? 16 : 10);
                    if (isValidCodePoint(value)) {
                        out.append("&#");
                        if (hex) {
                            out.append('x');
                        } else {
                            for (int pad = length; pad < 3; pad++) {
                                out.append('0');
                            }
                        }
                        out.append(s, significant, end).append(';');
                        return end + 1;
                    }
                }
            }
            out.append("&amp;");
            return p;
        }
        int letters = p;
        while (letters < n && TextUtils.isAsciiLetter(s.charAt(letters))) {
            letters++;
        }
        int digitsEnd = letters;
        while (digitsEnd < n && TextUtils.isAsciiDigit(s.charAt(digitsEnd))) {
            digitsEnd++;
        }
        int letterCount = letters - p;
        if (letterCount >= 2
                && letterCount <= 8
                && digitsEnd - letters <= 2
                && digitsEnd < n
                && s.charAt(digitsEnd) == ';') {
            String name = s.substring(p, digitsEnd);
            if (xml && EntityNames.isXml(name)) {
                out.append('&').append(name).append(';');
                return digitsEnd + 1;
            }
            if (EntityNames.isAllowed(name)) {
                if (xml) {
                    out.append(CharacterReferences.named(name + ";"));
                } else {
                    out.append('&').append(name).append(';');
                }
                return digitsEnd + 1;
            }
        }
        out.append("&amp;");
        return p;
    }
}
