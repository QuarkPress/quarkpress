package org.quarkpress.lib.wpshortcode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The positions of the exact closing tags {@code [/name]} of a set of names in one text, collected in one pass.
 * For each name, lookups must ask for non-decreasing positions; each is then an amortized constant-time pointer
 * move, which keeps "the first closing tag anywhere later" linear overall. Not thread-safe.
 */
final class ClosingTags {

    private static final class Positions {
        private int[] values = new int[4];
        private int size;
        private int cursor;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }
    }

    private final Map<String, Positions> byName = new HashMap<>();

    ClosingTags(String text, Set<String> names, int longestName) {
        ForwardIndex closeBrackets = new ForwardIndex(text, ']');
        for (int i = text.indexOf("[/"); i >= 0; i = text.indexOf("[/", i + 2)) {
            int nameStart = i + 2;
            int close = closeBrackets.next(nameStart);
            if (close < 0) {
                break;
            }
            int length = close - nameStart;
            if (length > 0 && length <= longestName) {
                String name = text.substring(nameStart, close);
                if (names.contains(name)) {
                    byName.computeIfAbsent(name, k -> new Positions()).add(i);
                }
            }
        }
    }

    /** The start of the first closing tag of {@code name} at or after {@code from}, or -1. */
    int first(String name, int from) {
        Positions p = byName.get(name);
        if (p == null) {
            return -1;
        }
        while (p.cursor < p.size && p.values[p.cursor] < from) {
            p.cursor++;
        }
        return p.cursor < p.size ? p.values[p.cursor] : -1;
    }
}
