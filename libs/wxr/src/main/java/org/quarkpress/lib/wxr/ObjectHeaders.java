package org.quarkpress.lib.wxr;

/**
 * The WordPress Importer rewrites every object header {@code O:<digits>:"<class>":} in a serialized meta value to
 * {@code O:8:"stdClass":} before unserializing it, wherever the header appears, also inside string payloads. A
 * header is {@code O:}, one or more ASCII digits, {@code :"}, one or more characters other than {@code "}, then
 * {@code ":}. Matches do not overlap; scanning resumes after each match, or one character later when there is
 * none. Linear: each scan for a class name stops at the next quote.
 */
final class ObjectHeaders {

    private static final String STD_CLASS = "O:8:\"stdClass\":";

    private ObjectHeaders() {}

    static String toStdClass(String s) {
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < s.length()) {
            int end = headerEnd(s, i);
            if (end < 0) {
                i++;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(s.length() + 16);
            }
            out.append(s, copied, i).append(STD_CLASS);
            copied = end;
            i = end;
        }
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }

    /** The index just past a header starting at {@code i}, or -1. */
    private static int headerEnd(String s, int i) {
        int n = s.length();
        if (i + 1 >= n || s.charAt(i) != 'O' || s.charAt(i + 1) != ':') {
            return -1;
        }
        int j = i + 2;
        int digits = j;
        while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
            j++;
        }
        if (j == digits || j + 1 >= n || s.charAt(j) != ':' || s.charAt(j + 1) != '"') {
            return -1;
        }
        j += 2;
        int name = j;
        while (j < n && s.charAt(j) != '"') {
            j++;
        }
        if (j == name || j + 1 >= n || s.charAt(j + 1) != ':') {
            return -1;
        }
        return j + 2;
    }
}
