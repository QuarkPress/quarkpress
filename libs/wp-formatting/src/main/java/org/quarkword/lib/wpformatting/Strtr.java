package org.quarkword.lib.wpformatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PHP's {@code strtr} with a replacement table: at each position the longest matching key is replaced, replaced
 * text is never scanned again, and an empty key is ignored. A trie keeps the cost at O(length × longest key).
 */
final class Strtr {

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        String replacement;
    }

    private Strtr() {}

    static String apply(String text, Map<String, String> pairs) {
        Objects.requireNonNull(text, "text");
        Node root = new Node();
        boolean any = false;
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            String key = Objects.requireNonNull(pair.getKey(), "key");
            String value = Objects.requireNonNull(pair.getValue(), "replacement");
            if (key.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int k = 0; k < key.length(); k++) {
                node = node.next.computeIfAbsent(key.charAt(k), c -> new Node());
            }
            node.replacement = value;
            any = true;
        }
        if (!any) {
            return text;
        }
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        int length = text.length();
        while (i < length) {
            Node node = root;
            String replacement = null;
            int matched = 0;
            for (int j = i; j < length; j++) {
                node = node.next.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.replacement != null) {
                    replacement = node.replacement;
                    matched = j + 1 - i;
                }
            }
            if (replacement == null) {
                i++;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(length + 16);
            }
            out.append(text, copied, i).append(replacement);
            i += matched;
            copied = i;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, length).toString();
    }
}
