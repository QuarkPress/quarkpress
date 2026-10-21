package org.quarkpress.lib.wpkses;

/**
 * WordPress's esc_attr and esc_html, which are the same for UTF-8 text: character references are normalized, '&' is
 * not escaped again when it starts one (except {@code &apos;}, which HTML 4.01 does not know), and
 * {@code < > " '} become {@code &lt; &gt; &quot; &#039;}. {@link #specialChars} is the same without the quote
 * encoding.
 */
final class HtmlEscaper {

    private HtmlEscaper() {}

    static String escape(String s) {
        return escape(s, true);
    }

    /**
     * WordPress's {@code _wp_specialchars} with its defaults (no quotes, no double encoding): like {@link #escape}, but
     * quotes are left as they are.
     */
    static String specialChars(String s) {
        return escape(s, false);
    }

    private static String escape(String s, boolean quotes) {
        if (!hasSpecialCharacter(s)) {
            return s;
        }
        String normalized = EntityNormalizer.normalize(s, false);
        StringBuilder out = new StringBuilder(normalized.length() + 16);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '&' -> {
                    // After normalization every '&' starts a reference that ends in ';'.
                    if (normalized.startsWith("&apos;", i)) {
                        out.append("&amp;apos;");
                        i += "&apos;".length() - 1;
                    } else {
                        out.append('&');
                    }
                }
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(quotes ? "&quot;" : "\"");
                case '\'' -> out.append(quotes ? "&#039;" : "'");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean hasSpecialCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                return true;
            }
        }
        return false;
    }
}
