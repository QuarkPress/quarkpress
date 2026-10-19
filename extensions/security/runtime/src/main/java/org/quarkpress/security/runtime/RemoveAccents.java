package org.quarkpress.security.runtime;

import java.text.Normalizer;

/**
 * Replaces accented Latin letters with base letters the way WordPress does for its default locale ({@code en_US}),
 * which is how it builds user logins. The text is first composed (NFC). A letter in the ranges WordPress's table
 * covers (Latin-1 Supplement and Latin Extended-A letters, the Vietnamese and pinyin vowels, and the comma-below
 * letters) becomes its base letter by dropping the marks of its decomposition; the few letters that do not decompose
 * that way (ligatures, the sharp s, stroked letters, the Latin alpha, the euro and pound signs) use an explicit
 * list. Everything else is kept. Other locales (German, Danish, Catalan, Serbian, Bosnian) change a few letters in
 * WordPress; QuarkPress always uses the default locale's rules.
 */
public final class RemoveAccents {

    private RemoveAccents() {}

    public static String enUs(String text) {
        if (isAscii(text)) {
            return text;
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(composed.length());
        int i = 0;
        while (i < composed.length()) {
            int c = composed.codePointAt(i);
            i += Character.charCount(c);
            String replacement = explicit(c);
            if (replacement != null) {
                out.append(replacement);
            } else if (hasBaseLetter(c)) {
                appendBaseLetters(c, out);
            } else {
                out.appendCodePoint(c);
            }
        }
        return out.toString();
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Letters and signs that do not decompose into a base letter and marks, with their replacement. */
    private static String explicit(int c) {
        return switch (c) {
            case 0x00A3 -> "";
            case 0x00AA -> "a";
            case 0x00BA -> "o";
            case 0x00C6 -> "AE";
            case 0x00D0 -> "D";
            case 0x00D8 -> "O";
            case 0x00DE -> "TH";
            case 0x00DF -> "s";
            case 0x00E6 -> "ae";
            case 0x00F0 -> "d";
            case 0x00F8 -> "o";
            case 0x00FE -> "th";
            case 0x0110 -> "D";
            case 0x0111 -> "d";
            case 0x0126 -> "H";
            case 0x0127 -> "h";
            case 0x0131 -> "i";
            case 0x0132 -> "IJ";
            case 0x0133 -> "ij";
            case 0x0138 -> "k";
            case 0x013F -> "L";
            case 0x0140 -> "l";
            case 0x0141 -> "L";
            case 0x0142 -> "l";
            case 0x0149 -> "n";
            case 0x014A -> "N";
            case 0x014B -> "n";
            case 0x0152 -> "OE";
            case 0x0153 -> "oe";
            case 0x0166 -> "T";
            case 0x0167 -> "t";
            case 0x017F -> "s";
            case 0x018F -> "E";
            case 0x01DD -> "e";
            case 0x0251 -> "a";
            case 0x20AC -> "E";
            default -> null;
        };
    }

    /** The code points WordPress replaces with the base letter of their decomposition. */
    private static boolean hasBaseLetter(int c) {
        return (c >= 0x00C0 && c <= 0x017F && c != 0x00D7 && c != 0x00F7)
                || c == 0x01A0
                || c == 0x01A1
                || c == 0x01AF
                || c == 0x01B0
                || (c >= 0x01CD && c <= 0x01DC)
                || (c >= 0x0218 && c <= 0x021B)
                || (c >= 0x1EA0 && c <= 0x1EF9);
    }

    private static void appendBaseLetters(int c, StringBuilder out) {
        String decomposed = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFD);
        for (int i = 0; i < decomposed.length(); i++) {
            char d = decomposed.charAt(i);
            if (Character.getType(d) != Character.NON_SPACING_MARK) {
                out.append(d);
            }
        }
    }
}
