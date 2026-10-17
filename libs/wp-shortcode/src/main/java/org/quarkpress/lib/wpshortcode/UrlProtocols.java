package org.quarkpress.lib.wpshortcode;

import java.util.List;
import java.util.Set;

/**
 * WordPress's wp_kses_bad_protocol with its default allowed protocols: values starting with {@code http://} or
 * {@code https://} pass; otherwise, up to six passes, the text before the first colon ({@code :},
 * {@code &#58;}, {@code &#x3a;} or {@code &colon;}, any case and leading zeros) is checked against the list, after
 * decoding numeric references and dropping spaces and controls, and is removed when not allowed. Text before
 * the colon that contains {@code /?} is not a scheme. {@code feed:} may wrap at most two more schemes. A value
 * that still changes after six passes becomes "".
 */
final class UrlProtocols {

    static final List<String> ALLOWED = List.of(
            "http", "https", "ftp", "ftps", "mailto", "news", "irc", "irc6", "ircs", "gopher", "nntp", "feed", "telnet",
            "mms", "rtsp", "sms", "svn", "tel", "fax", "xmpp", "webcal", "urn");

    private static final Set<String> ALLOWED_SET = Set.copyOf(ALLOWED);

    private static final Set<String> URI_ATTRIBUTES = Set.of(
            "action",
            "archive",
            "background",
            "cite",
            "classid",
            "codebase",
            "data",
            "formaction",
            "href",
            "icon",
            "longdesc",
            "manifest",
            "poster",
            "profile",
            "src",
            "usemap",
            "xmlns");

    private static final int MAX_PASSES = 6;

    private UrlProtocols() {}

    static boolean isUriAttribute(String lowercaseName) {
        return URI_ATTRIBUTES.contains(lowercaseName);
    }

    static String strip(String value) {
        String s = withoutNulls(value);
        if (s.startsWith("https://") || s.startsWith("http://")) {
            return s;
        }
        String before;
        int passes = 0;
        do {
            before = s;
            s = stripOnce(s, 1);
        } while (!before.equals(s) && ++passes < MAX_PASSES);
        return before.equals(s) ? s : "";
    }

    private static String stripOnce(String content, int depth) {
        String s = terminateColonReferences(content);
        int[] colon = firstColon(s);
        if (colon == null) {
            return s;
        }
        String scheme = s.substring(0, colon[0]);
        if (scheme.contains("/?")) {
            return s;
        }
        String rest = Chars.phpTrim(s.substring(colon[1]));
        String prefix = allowedPrefix(scheme);
        if (prefix.equals("feed:")) {
            if (depth > 2) {
                return "";
            }
            rest = stripOnce(rest, depth + 1);
            if (rest.isEmpty() || rest.equals("0")) {
                return rest;
            }
        }
        return prefix + rest;
    }

    /** kses' NUL removal: the controls, then every run of backslashes followed by zeros. */
    private static String withoutNulls(String value) {
        String s = Chars.removeControls(value);
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) != '\\') {
                out.append(s.charAt(i++));
                continue;
            }
            int slashesEnd = i;
            while (slashesEnd < s.length() && s.charAt(slashesEnd) == '\\') {
                slashesEnd++;
            }
            int zerosEnd = slashesEnd;
            while (zerosEnd < s.length() && s.charAt(zerosEnd) == '0') {
                zerosEnd++;
            }
            if (zerosEnd == slashesEnd) {
                out.append(s, i, slashesEnd);
            }
            i = zerosEnd;
        }
        return out.toString();
    }

    /** For the "&#" at i: the end of "&#" + zeros + "58", or of "&#x" + zeros + "3a" (any case); else -1. */
    private static int colonReferenceBody(String s, int i) {
        int n = s.length();
        int j = i + 2;
        boolean hex = j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
        if (hex) {
            j++;
        }
        while (j < n && s.charAt(j) == '0') {
            j++;
        }
        if (j + 2 > n) {
            return -1;
        }
        if (hex) {
            return s.charAt(j) == '3' && (s.charAt(j + 1) == 'a' || s.charAt(j + 1) == 'A') ? j + 2 : -1;
        }
        return s.charAt(j) == '5' && s.charAt(j + 1) == '8' ? j + 2 : -1;
    }

    /** Adds the missing ';' to a colon reference that is followed by neither ';' nor another digit. */
    private static String terminateColonReferences(String s) {
        int i = s.indexOf("&#");
        if (i < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 4);
        int last = 0;
        while (i >= 0) {
            int body = colonReferenceBody(s, i);
            if (body >= 0) {
                boolean hex = s.charAt(i + 2) == 'x' || s.charAt(i + 2) == 'X';
                boolean terminated = body < s.length()
                        && (s.charAt(body) == ';' || (hex ? isHexDigit(s.charAt(body)) : isDigit(s.charAt(body))));
                if (!terminated) {
                    out.append(s, last, body).append(';');
                    last = body;
                }
            }
            i = s.indexOf("&#", i + 2);
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    /** The first colon or colon reference as {start, end}, or null. */
    private static int[] firstColon(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                return new int[] {i, i + 1};
            }
            if (c == '&') {
                if (Chars.startsWithIgnoreAsciiCase(s, i, "&colon;")) {
                    return new int[] {i, i + 7};
                }
                if (s.startsWith("&#", i)) {
                    int body = colonReferenceBody(s, i);
                    if (body >= 0 && body < s.length() && s.charAt(body) == ';') {
                        return new int[] {i, body + 1};
                    }
                }
            }
        }
        return null;
    }

    /** "scheme:" when the decoded, compacted, lowercased scheme is allowed; otherwise "". */
    private static String allowedPrefix(String scheme) {
        String decoded = decodeReferences(decodeReferences(scheme, false), true);
        StringBuilder compact = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length(); i++) {
            if (!Chars.isSpace(decoded.charAt(i))) {
                compact.append(decoded.charAt(i));
            }
        }
        String name = Chars.asciiLower(withoutNulls(compact.toString()));
        return ALLOWED_SET.contains(name) ? name + ":" : "";
    }

    /** One pass over "&#digits;" (or "&#xhex;"), each replaced by the char of its value modulo 256 (PHP chr). */
    private static String decodeReferences(String s, boolean hex) {
        int i = s.indexOf("&#");
        if (i < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (i >= 0) {
            int j = i + 2;
            if (hex) {
                if (j < s.length() && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                    j++;
                } else {
                    i = s.indexOf("&#", i + 2);
                    continue;
                }
            }
            int start = j;
            int value = 0;
            while (j < s.length() && (hex ? isHexDigit(s.charAt(j)) : isDigit(s.charAt(j)))) {
                value = (value * (hex ? 16 : 10) + Character.digit(s.charAt(j), 16)) & 0xFF;
                j++;
            }
            if (j > start && j < s.length() && s.charAt(j) == ';') {
                out.append(s, last, i).append((char) value);
                last = j + 1;
                i = s.indexOf("&#", last);
            } else {
                i = s.indexOf("&#", i + 2);
            }
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
