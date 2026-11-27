package org.quarkword.lib.wpformatting;

import java.util.Objects;

/**
 * "The next {@code needle} at or after position i" over one text. It remembers its last answer, so a scan whose
 * start positions never decrease reads each char of the text a bounded number of times; any start is answered
 * correctly.
 */
final class Forward {

    private final String text;
    private final String needle;
    private int lastFrom = Integer.MAX_VALUE;
    private int lastAnswer = -1;

    Forward(String text, String needle) {
        this.text = Objects.requireNonNull(text, "text");
        this.needle = Objects.requireNonNull(needle, "needle");
    }

    /** The first index at or after {@code i} where the needle starts, or -1. */
    int from(int i) {
        int start = Math.max(i, 0);
        if (start >= lastFrom && (lastAnswer < 0 || start <= lastAnswer)) {
            return lastAnswer;
        }
        lastFrom = start;
        lastAnswer = text.indexOf(needle, start);
        return lastAnswer;
    }
}
