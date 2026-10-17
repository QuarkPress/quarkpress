package org.quarkpress.core.runtime;

import java.util.Objects;

/** The rules for option names, shared by readers and writers. */
public final class OptionNames {

    /** Characters, as both databases count them (code points). */
    public static final int MAX_LENGTH = 191;

    private OptionNames() {}

    /**
     * Trims space, tab, line feed, carriage return, NUL and vertical tab from both ends (what PHP's {@code trim} removes,
     * as WordPress does for option names) and checks the rest: not empty and not {@code "0"} (WordPress rejects both
     * with {@code empty()}), at most 191 characters, no NUL, no unpaired surrogate.
     */
    public static String normalize(String name) {
        Objects.requireNonNull(name, "name");
        int start = 0;
        int end = name.length();
        while (start < end && isTrimmed(name.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(name.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            throw new IllegalArgumentException("option name is empty");
        }
        if (end - start == 1 && name.charAt(start) == '0') {
            throw new IllegalArgumentException("option name is \"0\", which WordPress treats as empty");
        }
        int characters = 0;
        for (int i = start; i < end; i++) {
            char c = name.charAt(i);
            if (c == '\u0000') {
                throw new IllegalArgumentException("option name contains U+0000");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= end || !Character.isLowSurrogate(name.charAt(i + 1))) {
                    throw new IllegalArgumentException("option name contains an unpaired surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("option name contains an unpaired surrogate");
            }
            characters++;
            if (characters > MAX_LENGTH) {
                throw new IllegalArgumentException("option name is longer than " + MAX_LENGTH + " characters");
            }
        }
        return name.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000' || c == '\u000B';
    }
}
