package org.quarkpress.lib.wpkses;

/** wp_kses_split2: the replacement for one token. */
final class TagHandler {

    private TagHandler() {}

    static String handle(String rawToken, KsesRun run) {
        String token = TextUtils.stripSlashes(rawToken);
        if (!token.startsWith("<")) {
            return "&gt;";
        }
        if (isBogusComment(token)) {
            String inner = run.fixedPoint(token.substring(2, token.length() - 1));
            return inner == null ? "" : "<" + token.charAt(1) + inner + ">";
        }
        if (token.startsWith("<!--")) {
            String inner = run.fixedPoint(token.replace("<!--", "").replace("-->", ""));
            if (inner == null || inner.isEmpty()) {
                return "";
            }
            return "<!--" + collapseDashes(inner) + "-->";
        }
        return element(token, run);
    }

    /**
     * A token the HTML parser reads as a bogus comment: "</" followed by a character that is not an ASCII letter,
     * or "<!" followed by an ASCII lowercase letter; then anything but '>' up to a final '>'.
     */
    static boolean isBogusComment(String token) {
        int n = token.length();
        if (n < 4 || token.charAt(n - 1) != '>' || token.indexOf('>', 3) != n - 1) {
            return false;
        }
        char second = token.charAt(1);
        char third = token.charAt(2);
        return (second == '/' && !TextUtils.isAsciiLetter(third)) || (second == '!' && third >= 'a' && third <= 'z');
    }

    /**
     * Runs of two or more '-' become one, then one '-' is removed from the end, or from just before a final line
     * feed (where PCRE's '$' also matches).
     */
    static String collapseDashes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '-' || i == 0 || s.charAt(i - 1) != '-') {
                out.append(c);
            }
        }
        int last = out.length() - 1;
        if (last >= 0 && out.charAt(last) == '-') {
            out.setLength(last);
        } else if (last >= 1 && out.charAt(last) == '\n' && out.charAt(last - 1) == '-') {
            out.deleteCharAt(last - 1);
        }
        return out.toString();
    }

    /**
     * A start or end tag: optional whitespace, an optional '/' with optional whitespace, a name of ASCII letters,
     * digits and '-', attribute text without '>', and an optional final '>'. Anything else is removed, as are
     * elements the allowlist does not name.
     */
    private static String element(String token, KsesRun run) {
        int n = token.length();
        int i = 1;
        while (i < n && TextUtils.isPcreSpace(token.charAt(i))) {
            i++;
        }
        boolean closing = false;
        if (i < n && token.charAt(i) == '/') {
            closing = true;
            i++;
            while (i < n && TextUtils.isPcreSpace(token.charAt(i))) {
                i++;
            }
        }
        int nameStart = i;
        while (i < n && (TextUtils.isAsciiAlnum(token.charAt(i)) || token.charAt(i) == '-')) {
            i++;
        }
        if (i == nameStart) {
            return "";
        }
        int attrEnd = token.charAt(n - 1) == '>' ? n - 1 : n;
        int greaterThan = token.indexOf('>', i);
        if (greaterThan >= 0 && greaterThan < attrEnd) {
            return "";
        }
        String name = token.substring(nameStart, i);
        ElementRule rule = run.allowlist().elements().get(TextUtils.asciiLower(name));
        if (rule == null) {
            return "";
        }
        if (closing) {
            return "</" + name + ">";
        }
        if (!rule.allowsOpeningTags()) {
            return "";
        }
        return TagBuilder.build(name, token.substring(i, attrEnd), rule, run);
    }
}
