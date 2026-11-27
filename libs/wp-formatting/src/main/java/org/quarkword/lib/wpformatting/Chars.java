package org.quarkword.lib.wpformatting;

/**
 * Character classes of WordPress's formatters. WordPress matches bytes with ASCII-only classes; on valid UTF-8 that
 * gives the same results on UTF-16 chars when every non-ASCII char is neither a space, a letter nor a digit.
 */
final class Chars {

    private Chars() {}

    /** A whitespace char of WordPress's patterns: space, tab, line feed, vertical tab, form feed or carriage return. */
    static boolean isPcreSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** A char PHP's {@code trim} removes: space, tab, line feed, carriage return, NUL or vertical tab (not form feed). */
    static boolean isPhpTrim(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000b';
    }

    static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** ASCII letter, digit or underscore. */
    static boolean isAsciiWord(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c) || c == '_';
    }
}
