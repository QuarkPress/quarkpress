package org.quarkpress.lib.wpshortcode;

/**
 * Finds the next occurrence of one char at or after a position. A query at or after the previous one reuses
 * its answer when it can, so a series of forward queries costs time linear in the text; a query a few chars
 * behind the previous one scans only that gap. Not thread-safe.
 */
final class ForwardIndex {

    private final String text;
    private final char target;
    private boolean cached;
    private int from;
    private int found;

    ForwardIndex(String text, char target) {
        this.text = text;
        this.target = target;
    }

    /** The index of the first target char at or after {@code pos}, or -1. */
    int next(int pos) {
        if (cached) {
            if (pos < from) {
                for (int i = pos; i < from; i++) {
                    if (text.charAt(i) == target) {
                        return i;
                    }
                }
                return found;
            }
            if (found < 0 || pos <= found) {
                return found;
            }
        }
        cached = true;
        from = pos;
        found = text.indexOf(target, pos);
        return found;
    }
}
