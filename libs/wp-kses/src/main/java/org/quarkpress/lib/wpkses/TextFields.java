package org.quarkpress.lib.wpkses;

import java.util.Objects;

/**
 * WordPress's sanitizing of single-line and multi-line text fields ({@code sanitize_text_field},
 * {@code sanitize_textarea_field}), in work linear in the input: a removal WordPress repeats until nothing is left to
 * remove is done in one pass with a stack, which reaches the same result because the removed sequences cannot
 * overlap. {@code null} arguments throw {@code NullPointerException}; no input throws otherwise.
 */
public final class TextFields {

    private TextFields() {}

    /**
     * WordPress's text-field sanitizing: text with a lone surrogate (invalid UTF-8) becomes empty; when it holds a
     * '<', a lone '<' is escaped, tags are stripped (script and style elements with their content) and a '<' before a
     * line feed is escaped; runs of spaces, tabs and line breaks become one space; the ends are trimmed (NUL included);
     * percent-encoded octets are removed until none is left, and when any was, runs of spaces become one and the ends
     * are trimmed again.
     */
    public static String textField(String text) {
        return field(Objects.requireNonNull(text, "text"), false);
    }

    /** {@link #textField} keeping tabs and line breaks (WordPress's textarea-field sanitizing). */
    public static String textareaField(String text) {
        return field(Objects.requireNonNull(text, "text"), true);
    }

    private static String field(String text, boolean keepNewlines) {
        if (hasLoneSurrogate(text)) {
            return "";
        }
        String filtered = text;
        if (filtered.indexOf('<') >= 0) {
            filtered = Kses.lessThan(filtered);
            filtered = Kses.stripAllTags(filtered, false);
            filtered = filtered.replace("<\n", "&lt;\n");
        }
        if (!keepNewlines) {
            filtered = collapse(filtered, true);
        }
        filtered = trim(filtered);
        String removed = removeOctets(filtered, new long[1]);
        return removed.length() == filtered.length() ? filtered : trim(collapse(removed, false));
    }

    /**
     * The text with every percent-encoded octet ({@code %} and two hexadecimal digits) removed, again and again until
     * none is left ({@code %%4141} gives the empty string). Adds the characters looked at to {@code work[0]}, so a
     * caller with a work budget can count them.
     */
    public static String removeOctets(String text, long[] work) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            work[0]++;
            out.append(text.charAt(i));
            int n = out.length();
            if (n >= 3 && out.charAt(n - 3) == '%' && hex(out.charAt(n - 2)) && hex(out.charAt(n - 1))) {
                out.setLength(n - 3);
            }
        }
        return out.toString();
    }

    /** Runs of whitespace as one space: spaces, tabs and line breaks ({@code all}), or only spaces. */
    private static String collapse(String text, boolean all) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inRun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean space = c == ' ' || (all && (c == '\t' || c == '\n' || c == '\r'));
            if (space) {
                if (!inRun) {
                    out.append(' ');
                }
                inRun = true;
            } else {
                out.append(c);
                inRun = false;
            }
        }
        return out.toString();
    }

    /** PHP's {@code trim}: spaces, tabs, line breaks, vertical tabs and NUL at both ends. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0;
    }

    private static boolean hex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
