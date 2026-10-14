package org.quarkpress.lib.wpblocks;

/** Attribute JSON helpers: locating the object in a delimiter, reading it, writing it comment-safe. */
final class AttributeJson {

    private AttributeJson() {}

    /**
     * Returns the index just after the {@code }} that balances the {@code {} at {@code open}, skipping
     * braces inside string literals (with backslash escapes), or -1 if the input ends first.
     */
    static int findEnd(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip the escaped character; if it is past the end the loop ends with -1
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
