package org.quarkword.lib.wpformatting;

/**
 * {@code convert_chars}: escapes each {@code &} that does not look like the start of a character reference. The
 * char after {@code &} is taken whatever it is (unless it is {@code #}), except that an {@code &} before a non-ASCII
 * char is always escaped; the rest of the name is checked only from
 * the char after it, so a name of 2 to 9 chars passes. An escaped {@code &} takes the char after it along, so that
 * char is never checked as an {@code &} of its own; a kept one does not.
 */
final class ConvertChars {

    private static final String ESCAPED = "&#038;";
    private static final int MAX_NAME_TAIL = 8;

    private ConvertChars() {}

    static String apply(String content) {
        int first = content.indexOf('&');
        if (first < 0) {
            return content;
        }
        StringBuilder out = null;
        int copied = 0;
        int i = first;
        int n = content.length();
        while (i >= 0 && i < n) {
            if (i + 1 >= n) {
                break; // a final & has no char after it
            }
            char next = content.charAt(i + 1);
            if (next == '#') {
                i = content.indexOf('&', i + 1);
                continue;
            }
            // WordPress matches UTF-8 bytes: after a non-ASCII char's first byte comes a continuation byte, which
            // never belongs to a name, so the & is always escaped
            if (next < 0x80 && nameTailFollows(content, i + 2)) {
                // nothing is matched here, so the char after & is looked at again: "&&a;" escapes its second &
                i = content.indexOf('&', i + 1);
                continue;
            }
            if (out == null) {
                out = new StringBuilder(n + 16);
            }
            out.append(content, copied, i).append(ESCAPED);
            copied = i + 1;
            // the escaped & and the char after it are consumed together: "&&x" keeps its second &
            i = content.indexOf('&', i + 2);
        }
        if (out == null) {
            return content;
        }
        return out.append(content, copied, n).toString();
    }

    /** 1 to 8 ASCII letters or digits 1 to 4 at {@code from}, then {@code ;}. */
    private static boolean nameTailFollows(String text, int from) {
        int n = text.length();
        int k = 0;
        while (k < MAX_NAME_TAIL && from + k < n && isNameChar(text.charAt(from + k))) {
            k++;
        }
        return k > 0 && from + k < n && text.charAt(from + k) == ';';
    }

    private static boolean isNameChar(char c) {
        return Chars.isAsciiLetter(c) || (c >= '1' && c <= '4');
    }
}
