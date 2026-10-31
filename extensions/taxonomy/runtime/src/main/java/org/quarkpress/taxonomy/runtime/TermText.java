package org.quarkpress.taxonomy.runtime;

import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.lib.wpkses.KsesContext;

/**
 * WordPress's save-time filters of a term's name and description, for every caller alike (they do not depend on
 * {@code unfiltered_html}). QuarkPress takes plain text and gives what a correctly slashed WordPress call stores, so a
 * backslash is kept.
 */
public final class TermText {

    private static final Kses KSES = Kses.wordPress();
    private static final KsesContext NAME = KsesContext.named("pre_term_name");

    private TermText() {}

    /**
     * WordPress's {@code sanitize_text_field}: text with a lone surrogate (invalid UTF-8) becomes empty; when it holds
     * a '<', a lone '<' is escaped, tags are stripped (script and style elements with their content) and a '<' before
     * a line feed is escaped; runs of spaces, tabs and line breaks become one space; the ends are trimmed as PHP's
     * {@code trim} does, NUL included; percent-encoded octets are removed until none is left, and then the spaces are
     * collapsed and the ends trimmed again. The work is linear in the length of the text.
     */
    public static String sanitizeTextField(String text) {
        return textField(text);
    }

    private static String textField(String text) {
        if (hasLoneSurrogate(text)) {
            return "";
        }
        String filtered = text;
        if (filtered.indexOf('<') >= 0) {
            filtered = Kses.lessThan(filtered);
            filtered = Kses.stripAllTags(filtered, false);
            filtered = filtered.replace("<\n", "&lt;\n");
        }
        filtered = trim(collapse(filtered, true), true);
        String removed = removeOctets(filtered, new long[1]);
        return removed.length() == filtered.length() ? filtered : trim(collapse(removed, false), true);
    }

    /**
     * The text with every percent-encoded octet ({@code %} and two hexadecimal digits) removed, again and again until
     * none is left ({@code %%4141} gives the empty string), in one pass: each character is appended and an octet that
     * the append completes is dropped at once. Removing the octets in another order (WordPress removes every copy of
     * the first one found, then looks again) leaves the same text, because two octets never overlap: a '%' is not a
     * hexadecimal digit. Adds the characters looked at to {@code work[0]}.
     */
    static String removeOctets(String text, long[] work) {
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

    /**
     * A name as WordPress stores it. WordPress filters the slashed text (a backslash, quote or double quote gets a
     * backslash and a NUL becomes a backslash and a zero): the steps of {@link #sanitizeTextField}, kses for names
     * (which unslashes, filters and slashes again), then '&amp;', '&lt;' and '&gt;' encoded; the result is unslashed.
     */
    public static String name(String text) {
        String slashed = textField(addSlashes(text));
        slashed = addSlashes(KSES.sanitize(stripSlashes(slashed), NAME));
        return stripSlashes(Kses.specialChars(slashed));
    }

    /** A description as WordPress stores it: kses with the tag set for term descriptions, not trimmed. */
    public static String description(String text) {
        return KSES.sanitize(text, KsesContext.PRE_TERM_DESCRIPTION);
    }

    /**
     * A name as WordPress's lookups by name filter it: the text is not slashed first, so a backslash is dropped (kses
     * unslashes it) and keeps the next character, and the result is unslashed once more. Use it for probes, and
     * {@link #name} for stored names.
     */
    public static String probeName(String text) {
        String slashed = addSlashes(KSES.sanitize(stripSlashes(textField(text)), NAME));
        return stripSlashes(Kses.specialChars(slashed));
    }

    /** PHP's {@code trim} of text that is not slashed: spaces, tabs, line breaks, vertical tabs and NUL. */
    public static String trimmed(String text) {
        return trim(text, true);
    }

    /** PHP's {@code addslashes}: a backslash before each quote, double quote and backslash; NUL as backslash, zero. */
    static String addSlashes(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) {
                out.append("\\0");
            } else {
                if (c == '\\' || c == '\'' || c == '"') {
                    out.append('\\');
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** PHP's {@code stripslashes}: a backslash is dropped and keeps the next character, which a zero turns into NUL. */
    private static String stripSlashes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
            } else if (i + 1 < text.length()) {
                i++;
                out.append(text.charAt(i) == '0' ? '\u0000' : text.charAt(i));
            }
        }
        return out.toString();
    }

    /** PHP 8's {@code strtolower}: only the letters A to Z are lowered. */
    public static String asciiLower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * Whether WordPress counts a raw name as missing: nothing left after trimming spaces, tabs, line breaks and
     * vertical tabs. A NUL is not trimmed, because WordPress trims the slashed text, where a NUL is a backslash and a
     * zero.
     */
    public static boolean blank(String text) {
        return trim(text).isEmpty();
    }

    /** Whether the text holds U+0000, which PostgreSQL cannot store in a text column. */
    public static boolean hasNul(String text) {
        return text.indexOf('\u0000') >= 0;
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

    private static String trim(String text) {
        return trim(text, false);
    }

    private static String trim(String text, boolean nul) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start), nul)) {
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1), nul)) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmed(char c, boolean nul) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || (nul && c == 0);
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
