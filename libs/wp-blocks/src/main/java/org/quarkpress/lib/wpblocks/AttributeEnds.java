package org.quarkpress.lib.wpblocks;

/**
 * Per-parse memo for the attribute terminator search. Attributes end at the first {@code }} that is
 * followed by whitespace and then {@code -->} or {@code /-->}. Callers try candidates in increasing
 * offset order, so the forward search is remembered and the total work is linear in the input.
 */
final class AttributeEnds {

    private final String s;
    private int found = -1; // most recently found terminator index, or -1
    private int scanned; // every '}' before this index has been checked
    private boolean exhausted; // no terminator exists at or after 'scanned'

    AttributeEnds(String s) {
        this.s = s;
    }

    /**
     * Returns the index of the first terminating {@code }} at or after {@code from}, or -1 if there is
     * none. A terminator is a {@code }} followed by at least one whitespace char and then {@code -->}
     * or {@code /-->}.
     */
    int terminatorFrom(int from) {
        if (found >= from) {
            return found;
        }
        if (exhausted) {
            return -1;
        }
        int n = s.length();
        for (int i = Math.max(from, scanned); i < n; i++) {
            if (s.charAt(i) == '}') {
                int after = DelimiterScanner.skipWs(s, i + 1);
                if (after > i + 1 && (s.startsWith("-->", after) || s.startsWith("/-->", after))) {
                    found = i;
                    scanned = i + 1;
                    return i;
                }
            }
        }
        scanned = n;
        exhausted = true;
        return -1;
    }
}
