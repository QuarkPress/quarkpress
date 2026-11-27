package org.quarkword.lib.wpformatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The block-level element names of wpautop, with the two lookups it makes: an exact name followed by a given kind
 * of char, and a name that is only a prefix of what follows (so {@code p} also matches {@code <progress>}).
 */
final class BlockNames {

    private static final List<String> NAMES = List.of(
            "table",
            "thead",
            "tfoot",
            "caption",
            "col",
            "colgroup",
            "tbody",
            "tr",
            "td",
            "th",
            "div",
            "dl",
            "dd",
            "dt",
            "ul",
            "ol",
            "li",
            "pre",
            "form",
            "map",
            "area",
            "blockquote",
            "address",
            "style",
            "p",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "hr",
            "fieldset",
            "legend",
            "section",
            "article",
            "aside",
            "hgroup",
            "header",
            "footer",
            "nav",
            "figure",
            "figcaption",
            "details",
            "menu",
            "summary");

    /** The names that end a {@code <br />} before them, matched as prefixes. */
    private static final List<String> BREAK_ENDERS =
            List.of("p", "li", "div", "dl", "dd", "dt", "th", "pre", "td", "ul", "ol");

    /** {@link #NAMES} grouped by first char, for ASCII lower-case letters. */
    private static final List<List<String>> BY_FIRST = group(NAMES);

    private BlockNames() {}

    private static List<List<String>> group(List<String> names) {
        List<List<String>> groups = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) {
            List<String> group = new ArrayList<>();
            for (String name : names) {
                if (name.charAt(0) == c) {
                    group.add(name);
                }
            }
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    private static List<String> candidates(String s, int i) {
        if (i < 0 || i >= s.length()) {
            return List.of();
        }
        char c = s.charAt(i);
        return c >= 'a' && c <= 'z' ? BY_FIRST.get(c - 'a') : List.of();
    }

    /** A block name starts at {@code i} and is followed by whitespace, {@code /} or {@code >}. */
    static boolean nameAtBoundary(String s, int i) {
        for (String name : candidates(s, i)) {
            int end = i + name.length();
            if (s.startsWith(name, i) && end < s.length()) {
                char next = s.charAt(end);
                if (Chars.isPcreSpace(next) || next == '/' || next == '>') {
                    return true;
                }
            }
        }
        return false;
    }

    /** A block name starts at {@code i} and is directly followed by {@code >}. */
    static boolean nameBeforeGreaterThan(String s, int i) {
        for (String name : candidates(s, i)) {
            int end = i + name.length();
            if (s.startsWith(name, i) && end < s.length() && s.charAt(end) == '>') {
                return true;
            }
        }
        return false;
    }

    /** Some block name is a prefix of the text at {@code i}. */
    static boolean prefixAt(String s, int i) {
        for (String name : candidates(s, i)) {
            if (s.startsWith(name, i)) {
                return true;
            }
        }
        return false;
    }

    /** One of the names that end a {@code <br />} before them is a prefix of the text at {@code i}. */
    static boolean breakEnderAt(String s, int i) {
        for (String name : BREAK_ENDERS) {
            if (s.startsWith(name, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The end of the block tag starting at {@code lt} (a {@code <}, an optional {@code /}, a block name as a
     * prefix, then everything up to the first {@code >}), or -1. {@code greaterThan} looks for {@code >}.
     */
    static int tagEnd(String s, int lt, Forward greaterThan) {
        if (lt >= s.length() || s.charAt(lt) != '<') {
            return -1;
        }
        int name = lt + 1;
        if (name < s.length() && s.charAt(name) == '/') {
            name++;
        }
        if (!prefixAt(s, name)) {
            return -1;
        }
        int gt = greaterThan.from(name);
        return gt < 0 ? -1 : gt + 1;
    }
}
