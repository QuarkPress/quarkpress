package org.quarkpress.lib.wpshortcode;

/**
 * The square-bracket entities of the shortcode pipeline. Literal {@code &#91;}/{@code &#93;} in the input are
 * normalized to {@code &#091;}/{@code &#093;} so that they survive; the HTML pass hides brackets as
 * {@code &#91;}/{@code &#93;}; the final unescape turns every {@code &#91;}/{@code &#93;} back into brackets.
 */
final class EntityCodec {

    private EntityCodec() {}

    static String normalize(String s) {
        return replaceBracketEntities(s, "&#091;", "&#093;");
    }

    static String unescape(String s) {
        return replaceBracketEntities(s, "[", "]");
    }

    static String encodeBrackets(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean bracket = c == '[' || c == ']';
            if (bracket && out == null) {
                out = new StringBuilder(s.length() + 16);
                out.append(s, 0, i);
            }
            if (out != null) {
                if (c == '[') {
                    out.append("&#91;");
                } else if (c == ']') {
                    out.append("&#93;");
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? s : out.toString();
    }

    /** Replaces each exact "&#91;" with {@code open} and each "&#93;" with {@code close}, left to right. */
    private static String replaceBracketEntities(String s, String open, String close) {
        int i = s.indexOf("&#9");
        if (i < 0) {
            return s;
        }
        StringBuilder out = null;
        int last = 0;
        while (i >= 0) {
            if (i + 4 < s.length() && s.charAt(i + 4) == ';' && (s.charAt(i + 3) == '1' || s.charAt(i + 3) == '3')) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 8);
                }
                out.append(s, last, i).append(s.charAt(i + 3) == '1' ? open : close);
                last = i + 5;
                i = s.indexOf("&#9", last);
            } else {
                i = s.indexOf("&#9", i + 1);
            }
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }
}
