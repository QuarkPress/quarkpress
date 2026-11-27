package org.quarkword.lib.wpformatting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * force_balance_tags: one forward pass over the text that closes open elements, drops closers of elements that are
 * not open and rewrites void and self-closed tags. Time is linear in the text: every char is read a bounded number
 * of times, the "next {@code >}" search only moves forward, and each element is pushed and popped at most once.
 */
final class BalanceTags {

    /** Elements that never have content; they are never pushed. */
    static final Set<String> VOID = Set.of(
            "area",
            "base",
            "basefont",
            "br",
            "col",
            "command",
            "embed",
            "frame",
            "hr",
            "img",
            "input",
            "isindex",
            "link",
            "meta",
            "param",
            "source",
            "track",
            "wbr");

    /** Elements that may directly contain an element of the same name. */
    static final Set<String> NESTABLE =
            Set.of("article", "aside", "blockquote", "details", "div", "figure", "object", "q", "section", "span");

    /** WordPress hides this text from the tag scanner while it works and puts it back at the end. */
    private static final String SPACED_COMMENT = "< !--";

    private static final String HIDDEN_COMMENT = "<    !--";

    private BalanceTags() {}

    static String apply(String text) {
        String s = escape(text);
        int n = s.length();
        StringBuilder out = new StringBuilder(n + 16);
        ArrayDeque<String> stack = new ArrayDeque<>();
        Map<String, Integer> open = new HashMap<>();
        Forward greaterThan = new Forward(s, ">");
        int copied = 0;
        int i = s.indexOf('<');
        while (i >= 0) {
            Tag tag = match(s, i, greaterThan);
            if (tag == null) {
                i = s.indexOf('<', i + 1);
                continue;
            }
            out.append(s, copied, i);
            if (tag.closing) {
                close(tag.name, out, stack, open);
            } else {
                openTag(tag, out, stack, open);
            }
            copied = tag.end;
            i = s.indexOf('<', copied);
        }
        out.append(s, copied, n);
        while (!stack.isEmpty()) {
            out.append("</").append(stack.pop()).append('>');
        }
        String result = out.toString();
        return result.contains(HIDDEN_COMMENT) ? result.replace(HIDDEN_COMMENT, SPACED_COMMENT) : result;
    }

    /** Hides {@code "< !--"} and turns each {@code <} before an ASCII digit into {@code &lt;}. */
    private static String escape(String text) {
        if (text.indexOf('<') < 0) {
            return text;
        }
        int n = text.length();
        StringBuilder out = new StringBuilder(n + 16);
        int copied = 0;
        for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) {
            if (text.startsWith(SPACED_COMMENT, i)) {
                out.append(text, copied, i).append(HIDDEN_COMMENT);
                copied = i + SPACED_COMMENT.length();
                i = copied - 1;
            } else if (i + 1 < n && Chars.isAsciiDigit(text.charAt(i + 1))) {
                out.append(text, copied, i).append("&lt;");
                copied = i + 1;
            }
        }
        return copied == 0 ? text : out.append(text, copied, n).toString();
    }

    /** A matched tag: {@code name} in lower case, the text between it and {@code >}, and the index after {@code >}. */
    private record Tag(boolean closing, String name, String space, String attributes, int end) {}

    /** The tag starting at the {@code <} at {@code lt}, or null when the text there is not a tag. */
    private static Tag match(String s, int lt, Forward greaterThan) {
        int n = s.length();
        int start = lt + 1;
        boolean closing = start < n && s.charAt(start) == '/';
        if (closing) {
            start++;
        }
        int customEnd = customNameEnd(s, start);
        if (customEnd > start) {
            Tag tag = rest(s, closing, start, customEnd, greaterThan);
            if (tag != null) {
                return tag;
            }
        }
        int end = start;
        while (end < n && (Chars.isAsciiWord(s.charAt(end)) || s.charAt(end) == ':')) {
            end++;
        }
        return end > start ? rest(s, closing, start, end, greaterThan) : null;
    }

    /**
     * The end of a custom element name at {@code start}: a lower-case ASCII letter, then lower-case letters, digits,
     * dots and underscores, a hyphen, and at least one more of those or a hyphen. {@code start} when there is none.
     */
    private static int customNameEnd(String s, int start) {
        int n = s.length();
        if (start >= n || !isLowerLetter(s.charAt(start))) {
            return start;
        }
        int i = start + 1;
        while (i < n && isCustomChar(s.charAt(i))) {
            i++;
        }
        if (i >= n || s.charAt(i) != '-') {
            return start;
        }
        int tail = ++i;
        while (i < n && (isCustomChar(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        return i > tail ? i : start;
    }

    /**
     * What follows a name ending at {@code nameEnd}: optional whitespace, an optional {@code /} and {@code >}; or
     * whitespace (kept), then anything up to the next {@code >} (the attributes).
     */
    private static Tag rest(String s, boolean closing, int nameStart, int nameEnd, Forward greaterThan) {
        int n = s.length();
        int i = nameEnd;
        while (i < n && Chars.isPcreSpace(s.charAt(i))) {
            i++;
        }
        int j = i < n && s.charAt(i) == '/' ? i + 1 : i;
        if (j < n && s.charAt(j) == '>') {
            String name = asciiLower(s, nameStart, nameEnd);
            return new Tag(closing, name, "", j > i ? "/" : "", j + 1);
        }
        if (i == nameEnd) {
            return null;
        }
        int gt = greaterThan.from(i);
        if (gt < 0) {
            return null;
        }
        String name = asciiLower(s, nameStart, nameEnd);
        return new Tag(closing, name, s.substring(nameEnd, i), Formatting.phpTrim(s.substring(i, gt)), gt + 1);
    }

    private static void close(String name, StringBuilder out, ArrayDeque<String> stack, Map<String, Integer> open) {
        if (!open.containsKey(name)) {
            return;
        }
        String top;
        do {
            top = stack.pop();
            open.computeIfPresent(top, (k, count) -> count == 1 ? null : count - 1);
            out.append("</").append(top).append('>');
        } while (!top.equals(name));
    }

    private static void openTag(Tag tag, StringBuilder out, ArrayDeque<String> stack, Map<String, Integer> open) {
        String name = tag.name;
        String attributes = tag.attributes;
        if (attributes.endsWith("/")) {
            if (VOID.contains(name)) {
                out.append('<').append(name).append(' ').append(attributes).append('>');
            } else {
                String kept = Formatting.phpTrim(attributes.substring(0, attributes.length() - 1));
                out.append('<')
                        .append(name)
                        .append(tag.space)
                        .append(kept)
                        .append("></")
                        .append(name)
                        .append('>');
            }
            return;
        }
        if (VOID.contains(name)) {
            out.append('<').append(name).append(' ').append(attributes).append("/>");
            return;
        }
        if (name.equals(stack.peek()) && !NESTABLE.contains(name)) {
            close(name, out, stack, open);
        }
        stack.push(name);
        open.merge(name, 1, Integer::sum);
        out.append('<').append(name).append(tag.space).append(attributes).append('>');
    }

    private static boolean isLowerLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isCustomChar(char c) {
        return isLowerLetter(c) || Chars.isAsciiDigit(c) || c == '.' || c == '_';
    }

    private static String asciiLower(String s, int start, int end) {
        char[] chars = new char[end - start];
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            chars[i - start] = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
        }
        return new String(chars);
    }
}
