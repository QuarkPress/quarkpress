package org.quarkpress.lib.wpkses;

import java.util.Objects;

/**
 * Finds the next occurrence of a fixed string at or after a position. A query at or after the previous one reuses
 * the previous search, so a scan whose positions only move forward costs linear time in total.
 */
final class Finder {

    private final String text;
    private final String needle;
    private int searchedFrom = -1;
    private int found = -1;

    Finder(String text, String needle) {
        this.text = Objects.requireNonNull(text, "text");
        this.needle = Objects.requireNonNull(needle, "needle");
    }

    /** Index of the first occurrence at or after {@code position}, or -1. */
    int next(int position) {
        if (searchedFrom >= 0 && position >= searchedFrom && (found < 0 || position <= found)) {
            return found;
        }
        searchedFrom = position;
        found = text.indexOf(needle, position);
        return found;
    }
}
