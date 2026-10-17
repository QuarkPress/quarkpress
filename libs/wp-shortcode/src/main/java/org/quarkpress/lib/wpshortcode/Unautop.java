package org.quarkpress.lib.wpshortcode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WordPress's shortcode_unautop: a paragraph that holds nothing but one registered shortcode (and spaces) is
 * replaced by the shortcode. Spaces are space, tab, CR, LF, U+00A0 and the text {@code &nbsp;}. The closing tag
 * of an enclosing shortcode is searched anywhere later, so a replacement can span paragraphs, as in WordPress.
 * Linear: each {@code <p>} is tried once, closing tags come from a {@link ClosingTags} index and the
 * "spaces then {@code </p>}" check is cached per position.
 */
final class Unautop {

    private Unautop() {}

    static String apply(String text, List<String> names) {
        if (names.isEmpty() || !text.contains("<p>")) {
            return text;
        }
        return new Run(text, names).apply();
    }

    private static final class Run {
        private final String text;
        private final NameTrie trie;
        private final ForwardIndex closeBrackets;
        private final ClosingTags closers;
        private final Map<Integer, Integer> paragraphEnds = new HashMap<>();

        Run(String text, List<String> names) {
            this.text = text;
            Set<String> unique = new LinkedHashSet<>(names);
            this.trie = new NameTrie(unique);
            this.closeBrackets = new ForwardIndex(text, ']');
            this.closers = new ClosingTags(text, unique, trie.longest());
        }

        String apply() {
            StringBuilder out = null;
            int last = 0;
            int p = text.indexOf("<p>");
            while (p >= 0) {
                int[] m = matchAt(p);
                if (m == null) {
                    p = text.indexOf("<p>", p + 1);
                    continue;
                }
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, last, p).append(text, m[0], m[1]);
                last = m[2];
                p = text.indexOf("<p>", last);
            }
            return out == null ? text : out.append(text, last, text.length()).toString();
        }

        /** {shortcode start, shortcode end, paragraph end} for the paragraph opened at p, or null. */
        private int[] matchAt(int p) {
            int n = text.length();
            int open = skipSpaces(p + 3);
            if (open + 1 >= n || text.charAt(open) != '[') {
                return null;
            }
            // every name that matches here, in registration order: when one fails at "</p>", the next is tried
            for (String name : trie.matchesAt(text, open + 1)) {
                int nameEnd = open + 1 + name.length();
                int close = closeBrackets.next(nameEnd);
                if (close < 0) {
                    return null;
                }
                boolean selfClosing = close > nameEnd && text.charAt(close - 1) == '/';
                if (!selfClosing) {
                    int closer = closers.first(name, close + 1);
                    if (closer >= 0) {
                        int shortcodeEnd = closer + name.length() + 3;
                        int end = paragraphEnd(shortcodeEnd);
                        if (end >= 0) {
                            return new int[] {open, shortcodeEnd, end};
                        }
                    }
                }
                int end = paragraphEnd(close + 1);
                if (end >= 0) {
                    return new int[] {open, close + 1, end};
                }
            }
            return null;
        }

        /** The index after spaces and "</p>" starting at pos, or -1. */
        private int paragraphEnd(int pos) {
            Integer cached = paragraphEnds.get(pos);
            if (cached != null) {
                return cached;
            }
            int i = skipSpaces(pos);
            int end = text.startsWith("</p>", i) ? i + 4 : -1;
            paragraphEnds.put(pos, end);
            return end;
        }

        private int skipSpaces(int from) {
            int i = from;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u00a0') {
                    i++;
                } else if (text.startsWith("&nbsp;", i)) {
                    i += 6;
                } else {
                    break;
                }
            }
            return i;
        }
    }
}
