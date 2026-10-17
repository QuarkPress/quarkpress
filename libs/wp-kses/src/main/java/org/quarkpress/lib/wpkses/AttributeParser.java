package org.quarkpress.lib.wpkses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The attribute part of a start tag, read the way WordPress 7.0+ reads it in wp_kses_hair through the HTML API's
 * tag processor. An original scanner: one pass, no regular expressions.
 *
 * <p>Observable behavior: attributes are separated by HTML whitespace or '/'; a name may start with '='; the first
 * occurrence of a lowercase name wins; an unclosed quote drops every attribute; carriage returns are normalized
 * and NUL becomes U+FFFD before character references are decoded.
 *
 * <p>Known edge: the text handed in here ends at the first '>' that the tag tokenizer found, so a '>' inside an
 * unquoted value ends the tag early, exactly as it does in WordPress; the rest of the value is then outside the
 * attribute text.
 */
final class AttributeParser {

    private AttributeParser() {}

    /**
     * The attributes of {@code attr} (the text between the element name and the closing '>'), first occurrence
     * of each lowercase name, in source order. Empty when a quoted value is not closed: the tag is incomplete.
     */
    static List<HairAttribute> parse(String attr) {
        Map<String, HairAttribute> found = new LinkedHashMap<>();
        int n = attr.length();
        int i = 0;
        while (true) {
            while (i < n && (TextUtils.isHtmlSpace(attr.charAt(i)) || attr.charAt(i) == '/')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int nameStart = i++; // the first character is always part of the name, even '='
            while (i < n && !endsName(attr.charAt(i))) {
                i++;
            }
            String name = TextUtils.asciiLower(withoutNul(attr.substring(nameStart, i)));
            int j = skipSpaces(attr, i);
            String value = null;
            if (j < n && attr.charAt(j) == '=') {
                j = skipSpaces(attr, j + 1);
                if (j >= n) {
                    value = "";
                } else if (attr.charAt(j) == '"' || attr.charAt(j) == '\'') {
                    int close = attr.indexOf(attr.charAt(j), j + 1);
                    if (close < 0) {
                        return List.of();
                    }
                    value = decodeValue(attr.substring(j + 1, close));
                    j = close + 1;
                } else {
                    int end = j;
                    while (end < n && !TextUtils.isHtmlSpace(attr.charAt(end))) {
                        end++;
                    }
                    value = decodeValue(attr.substring(j, end));
                    j = end;
                }
            }
            i = j;
            found.putIfAbsent(name, new HairAttribute(name, value));
        }
        return List.copyOf(found.values());
    }

    private static boolean endsName(char c) {
        return TextUtils.isHtmlSpace(c) || c == '/' || c == '=';
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && TextUtils.isHtmlSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** CR LF and lone CR become LF, NUL becomes U+FFFD, then character references are decoded. */
    static String decodeValue(String raw) {
        String text = raw.indexOf('\r') < 0 ? raw : raw.replace("\r\n", "\n").replace('\r', '\n');
        return CharacterReferences.decodeAttribute(withoutNul(text));
    }

    /** NUL becomes U+FFFD, as the HTML tokenizer does in attribute names and values. */
    private static String withoutNul(String s) {
        return s.indexOf('\0') < 0 ? s : s.replace('\0', TextUtils.REPLACEMENT_CHARACTER);
    }

    /** {@code & < > ' "} become {@code &amp; &lt; &gt; &apos; &quot;}; nothing else changes. */
    static String reencode(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            String replacement = switch (value.charAt(i)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '\'' -> "&apos;";
                case '"' -> "&quot;";
                default -> null;
            };
            if (replacement != null && out == null) {
                out = new StringBuilder(value.length() + 16).append(value, 0, i);
            }
            if (out != null) {
                if (replacement != null) {
                    out.append(replacement);
                } else {
                    out.append(value.charAt(i));
                }
            }
        }
        return out == null ? value : out.toString();
    }
}
