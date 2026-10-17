package org.quarkpress.core;

import java.util.Optional;

/** Shared rules of the typed ids. */
final class Ids {

    private static final int MAX_DIGITS = 19;

    private Ids() {}

    static void requirePositive(long value, String kind) {
        if (value <= 0) {
            throw new IllegalArgumentException(kind + " id must be positive: " + value);
        }
    }

    /**
     * Reads a canonical positive decimal: 1 to 19 ASCII digits, no sign, no leading zero, at most
     * {@link Long#MAX_VALUE}. Anything else is empty.
     */
    static Optional<Long> parse(CharSequence text) {
        if (text == null) {
            return Optional.empty();
        }
        int length = text.length();
        if (length == 0 || length > MAX_DIGITS || text.charAt(0) == '0') {
            return Optional.empty();
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return Optional.empty();
            }
            value = value * 10 + digit;
        }
        return Optional.of(value);
    }
}
