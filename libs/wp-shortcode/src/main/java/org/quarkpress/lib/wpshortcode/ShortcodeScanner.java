package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Finds the shortcodes of a fixed list of names in a text, left to right, without overlaps. A match is
 * {@code [}, an optional escape {@code [}, a listed name (earlier names win) that is not followed by an ASCII
 * word char or {@code -}, the attribute text up to the first {@code ]} (self-closing when it ends with
 * {@code /}), then, unless self-closing, everything up to the first exact {@code [/name]} anywhere later, and
 * finally one optional {@code ]}. Time is linear in the text for a fixed list of names. Immutable; each scan
 * keeps its own state.
 */
final class ShortcodeScanner {

    /** One match: its offsets in the scanned text, its parts, and whether it consumed a closing tag. */
    record Hit(int start, int end, ShortcodeMatch match, boolean enclosing) {}

    /** A text after replacing hits, and how many were replaced. */
    record Expansion(String text, int replacements) {}

    private final LinkedHashSet<String> names;
    private final NameTrie trie;

    ShortcodeScanner(List<String> names) {
        this.names = new LinkedHashSet<>(names);
        for (String name : this.names) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a shortcode name must not be empty");
            }
        }
        this.trie = new NameTrie(this.names);
    }

    List<Hit> scan(String text) {
        List<Hit> hits = new ArrayList<>();
        if (names.isEmpty()) {
            return hits;
        }
        Cursor cursor = new Cursor(text);
        int p = text.indexOf('[');
        while (p >= 0) {
            Hit hit = cursor.matchAt(p);
            if (hit == null) {
                p = text.indexOf('[', p + 1);
            } else {
                hits.add(hit);
                p = text.indexOf('[', hit.end());
            }
        }
        return hits;
    }

    /** Replaces every hit with {@code render}'s result; rendered text is never scanned again. */
    Expansion replace(String text, Function<Hit, String> render) {
        List<Hit> hits = scan(text);
        if (hits.isEmpty()) {
            return new Expansion(text, 0);
        }
        StringBuilder out = new StringBuilder(text.length() + 16 * hits.size());
        int last = 0;
        for (Hit hit : hits) {
            out.append(text, last, hit.start()).append(render.apply(hit));
            last = hit.end();
        }
        out.append(text, last, text.length());
        return new Expansion(out.toString(), hits.size());
    }

    /** The state of one scan. */
    private final class Cursor {
        private final String text;
        private final ForwardIndex closeBrackets;
        private final ClosingTags closers;

        Cursor(String text) {
            this.text = text;
            this.closeBrackets = new ForwardIndex(text, ']');
            this.closers = new ClosingTags(text, names, trie.longest());
        }

        Hit matchAt(int p) {
            int n = text.length();
            int q = p + 1;
            String openEscape = "";
            if (q < n && text.charAt(q) == '[') {
                openEscape = "[";
                q++;
            }
            String name = trie.firstAt(text, q);
            if (name == null) {
                return null;
            }
            int nameEnd = q + name.length();
            int close = closeBrackets.next(nameEnd);
            if (close < 0) {
                return null;
            }
            return hit(p, openEscape, name, nameEnd, close);
        }

        private Hit hit(int start, String openEscape, String name, int nameEnd, int close) {
            boolean selfClosing = close > nameEnd && text.charAt(close - 1) == '/';
            String attributeText = text.substring(nameEnd, selfClosing ? close - 1 : close);
            int end = close + 1;
            String content = "";
            boolean enclosing = false;
            if (!selfClosing) {
                int closer = closers.first(name, end);
                if (closer >= 0) {
                    content = text.substring(end, closer);
                    enclosing = true;
                    end = closer + name.length() + 3;
                }
            }
            String closeEscape = "";
            if (end < text.length() && text.charAt(end) == ']') {
                closeEscape = "]";
                end++;
            }
            ShortcodeMatch match = new ShortcodeMatch(
                    text.substring(start, end),
                    openEscape,
                    name,
                    attributeText,
                    selfClosing ? "/" : "",
                    content,
                    closeEscape);
            return new Hit(start, end, match, enclosing);
        }
    }
}
