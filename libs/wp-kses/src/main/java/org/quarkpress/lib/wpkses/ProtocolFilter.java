package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Objects;

/** wp_kses_bad_protocol strips URL schemes that are not in the allowed list. */
final class ProtocolFilter {

    /** At most six single passes; a sixth pass that still changes the value gives the empty string. */
    static final int MAX_PASSES = 6;

    private ProtocolFilter() {}

    static String filter(String content, List<String> protocols) {
        Objects.requireNonNull(protocols, "protocols");
        String s = NullFilter.clean(Objects.requireNonNull(content, "content"), false);
        if ((s.startsWith("https://") && protocols.contains("https"))
                || (s.startsWith("http://") && protocols.contains("http"))) {
            return s;
        }
        String before;
        int passes = 0;
        do {
            before = s;
            s = once(s, protocols, 1);
        } while (!before.equals(s) && ++passes < MAX_PASSES);
        return before.equals(s) ? s : "";
    }

    /** One pass; {@code depth} counts nested feed: schemes. */
    static String once(String content, List<String> protocols, int depth) {
        String s = addMissingColonSemicolons(content);
        int[] separator = firstSeparator(s);
        if (separator == null) {
            return s;
        }
        String scheme = s.substring(0, separator[0]);
        if (scheme.contains("/?")) {
            return s;
        }
        String rest = TextUtils.phpTrim(s.substring(separator[1]));
        String allowed = allowedScheme(scheme, protocols);
        if (allowed.equals("feed:")) {
            if (depth > 2) {
                return "";
            }
            rest = once(rest, protocols, depth + 1);
            if (TextUtils.phpEmpty(rest)) {
                return rest;
            }
        }
        return allowed + rest;
    }

    /**
     * Adds the missing semicolon: "&#58" (any leading zeros) and "&#x3a" not followed by ';' or another digit get
     * a ';'.
     */
    static String addMissingColonSemicolons(String s) {
        int amp = s.indexOf("&#");
        if (amp < 0) {
            return s;
        }
        StringBuilder out = null;
        int copied = 0;
        while (amp >= 0) {
            int end = colonReferenceEnd(s, amp);
            if (end < 0) {
                amp = s.indexOf("&#", amp + 2);
                continue;
            }
            boolean hex = s.charAt(amp + 2) == 'x' || s.charAt(amp + 2) == 'X';
            char next = end < s.length() ? s.charAt(end) : 0;
            boolean terminated = next == ';' || (hex ? TextUtils.isHexDigit(next) : TextUtils.isAsciiDigit(next));
            if (!terminated) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 8);
                }
                out.append(s, copied, end).append(';');
                copied = end;
            }
            amp = s.indexOf("&#", end);
        }
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }

    /** End of "&#" zeros "58" or "&#x" zeros "3a" (x and a in either case) at {@code amp}, or -1. */
    private static int colonReferenceEnd(String s, int amp) {
        int i = amp + 2;
        boolean hex = i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X');
        if (hex) {
            i++;
        }
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        if (hex) {
            return TextUtils.regionMatchesAsciiIgnoreCase(s, i, "3a") ? i + 2 : -1;
        }
        return s.startsWith("58", i) ? i + 2 : -1;
    }

    /**
     * Finds the first separator, as {start, end}: ':', "&#58;" or "&#x3a;" with any leading zeros (x and a in
     * either case), or "&colon;" in any case. Null when there is none.
     */
    static int[] firstSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                return new int[] {i, i + 1};
            }
            if (c != '&') {
                continue;
            }
            if (TextUtils.regionMatchesAsciiIgnoreCase(s, i, "&colon;")) {
                return new int[] {i, i + 7};
            }
            int end = colonReferenceEnd(s, i);
            if (end >= 0 && end < s.length() && s.charAt(end) == ';') {
                return new int[] {i, end + 1};
            }
        }
        return null;
    }

    /** The cleaned, lowercased scheme followed by ':' when it is allowed, else "". */
    static String allowedScheme(String scheme, List<String> protocols) {
        String decoded = TextUtils.decodeNumericBytes(scheme);
        StringBuilder compact = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (!TextUtils.isPcreSpace(c)) {
                compact.append(c);
            }
        }
        String cleaned = TextUtils.asciiLower(NullFilter.clean(compact.toString(), false));
        for (String protocol : protocols) {
            if (TextUtils.asciiLower(protocol).equals(cleaned)) {
                return cleaned + ":";
            }
        }
        return "";
    }
}
