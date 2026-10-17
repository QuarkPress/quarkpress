package org.quarkpress.lib.wxr;

/**
 * Escaping for the writer. Every value is first made XML-legal: a character XML cannot hold becomes U+FFFD, where
 * WordPress writes it raw and so produces a file no XML parser accepts.
 */
final class XmlText {

    static final char REPLACEMENT = '\uFFFD';

    private XmlText() {}

    /** The value with XML-illegal chars and unpaired surrogates replaced by U+FFFD; the same instance if clean. */
    static String sanitize(String s) {
        StringBuilder out = null;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (out != null) {
                    out.append(c).append(s.charAt(i + 1));
                }
                i++;
            } else if (XmlChars.isLegal(c)) {
                if (out != null) {
                    out.append(c);
                }
            } else {
                if (out == null) {
                    out = new StringBuilder(n).append(s, 0, i);
                }
                out.append(REPLACEMENT);
            }
        }
        return out == null ? s : out.toString();
    }

    /**
     * Element text: {@code &}, {@code <} and {@code >} escaped, and a carriage return written as {@code &#13;} so
     * that parsers do not turn it into a line feed.
     */
    static void text(StringBuilder out, String value) {
        String v = sanitize(value);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
    }

    /** A double-quoted attribute value; tab, LF and CR as references so attribute normalization keeps them. */
    static void attribute(StringBuilder out, String value) {
        String v = sanitize(value);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '"' -> out.append("&quot;");
                case '\t' -> out.append("&#9;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
    }

    /**
     * CDATA as WordPress writes it: {@code ]]>} becomes {@code ]]]]><![CDATA[>}, splitting the value into adjacent
     * sections; null and empty become {@code <![CDATA[]]>}. A carriage return closes the section, is written as
     * {@code &#13;} (inside CDATA a parser would turn it into a line feed), and a new section starts.
     */
    static void cdata(StringBuilder out, String value) {
        String v = value == null ? "" : sanitize(value);
        out.append("<![CDATA[");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\r') {
                out.append("]]>&#13;<![CDATA[");
            } else if (c == '>' && i >= 2 && v.charAt(i - 1) == ']' && v.charAt(i - 2) == ']') {
                out.append("]]><![CDATA[>");
            } else {
                out.append(c);
            }
        }
        out.append("]]>");
    }
}
