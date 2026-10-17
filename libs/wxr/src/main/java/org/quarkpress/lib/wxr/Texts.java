package org.quarkpress.lib.wxr;

/** Trimming with the character sets of XML and of PHP's trim(). */
final class Texts {

    private Texts() {}

    /** Longest input echoed into a problem detail or exception message. */
    static final int ECHO_LIMIT = 100;

    /** {@code s} cut to about {@link #ECHO_LIMIT} chars with an ellipsis, so hostile input cannot inflate the heap. */
    static String clip(String s) {
        if (s == null || s.length() <= ECHO_LIMIT) {
            return s;
        }
        int end = Character.isHighSurrogate(s.charAt(ECHO_LIMIT - 1)) ? ECHO_LIMIT - 1 : ECHO_LIMIT;
        return s.substring(0, end) + "\u2026";
    }

    /** Strips XML whitespace (space, tab, LF, CR) from both ends. */
    static String xmlTrim(String s) {
        return trim(s, false);
    }

    /** Strips what PHP's trim() strips by default: XML whitespace plus NUL and vertical tab. */
    static String phpTrim(String s) {
        return trim(s, true);
    }

    private static String trim(String s, boolean php) {
        int start = 0;
        int end = s.length();
        while (start < end && isTrimmed(s.charAt(start), php)) {
            start++;
        }
        while (end > start && isTrimmed(s.charAt(end - 1), php)) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isTrimmed(char c, boolean php) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || (php && (c == 0 || c == 0x0B));
    }
}
