package org.quarkpress.lib.wpblocks;

/** Block name grammar: {@code [a-z][a-z0-9_-]*} with an optional namespace of the same shape and a slash. */
final class BlockNames {

    static final String CORE = "core/";

    private BlockNames() {}

    static boolean isValid(String name) {
        int slash = name.indexOf('/');
        if (slash < 0) {
            return isSegment(name, 0, name.length());
        }
        return isSegment(name, 0, slash) && isSegment(name, slash + 1, name.length());
    }

    /** True if {@code s[from, to)} is a valid name segment. */
    static boolean isSegment(String s, int from, int to) {
        if (to - from < 1 || !isLower(s.charAt(from))) {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            if (!isNameChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    static boolean isNameChar(char c) {
        return isLower(c) || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /** Adds the {@code core/} namespace when the name has none. The name must already be valid. */
    static String qualify(String name) {
        return name.indexOf('/') < 0 ? CORE + name : name;
    }

    /** Drops the {@code core/} namespace, as WordPress does when writing delimiters. */
    static String shorten(String qualified) {
        return qualified.startsWith(CORE) ? qualified.substring(CORE.length()) : qualified;
    }
}
