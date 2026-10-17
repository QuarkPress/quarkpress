package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names in a trie, so the names that match at a position are found in time bounded by the longest name rather
 * than by the number of names. A name matches at a position when the text continues with it and the char after
 * it, if any, is not an ASCII word char or {@code -}. Immutable after construction.
 */
final class NameTrie {

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>(4);
        private int order = -1;
        private String name;
    }

    private final Node root = new Node();
    private final int longest;

    NameTrie(Collection<String> names) {
        int order = 0;
        int max = 0;
        for (String name : names) {
            Node node = root;
            for (int i = 0; i < name.length(); i++) {
                node = node.children.computeIfAbsent(name.charAt(i), c -> new Node());
            }
            if (node != root && node.order < 0) {
                node.order = order;
                node.name = name;
            }
            order++;
            max = Math.max(max, name.length());
        }
        this.longest = max;
    }

    int longest() {
        return longest;
    }

    /** The earliest-added name that matches at {@code at}, or null. */
    String firstAt(String text, int at) {
        Node best = null;
        Node node = root;
        for (int i = at; i < text.length(); i++) {
            node = node.children.get(text.charAt(i));
            if (node == null) {
                break;
            }
            if (node.order >= 0 && endsAt(text, i + 1) && (best == null || node.order < best.order)) {
                best = node;
            }
        }
        return best == null ? null : best.name;
    }

    /** Every name that matches at {@code at}, earliest-added first (at most one per length). */
    List<String> matchesAt(String text, int at) {
        List<Node> found = new ArrayList<>(2);
        Node node = root;
        for (int i = at; i < text.length(); i++) {
            node = node.children.get(text.charAt(i));
            if (node == null) {
                break;
            }
            if (node.order >= 0 && endsAt(text, i + 1)) {
                found.add(node);
            }
        }
        found.sort(Comparator.comparingInt(n -> n.order));
        List<String> names = new ArrayList<>(found.size());
        for (Node n : found) {
            names.add(n.name);
        }
        return names;
    }

    /** A name may end at {@code end} unless an ASCII word char or '-' follows. */
    private static boolean endsAt(String text, int end) {
        if (end >= text.length()) {
            return true;
        }
        char c = text.charAt(end);
        return !(Chars.isWord(c) || c == '-');
    }
}
