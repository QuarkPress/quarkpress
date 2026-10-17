package org.quarkpress.core;

import java.util.Optional;

/** Identifies a taxonomy term. Ids are positive and unique across the network. */
public record TermId(long value) {

    public TermId {
        Ids.requirePositive(value, "term");
    }

    public static TermId of(long value) {
        return new TermId(value);
    }

    /** Reads a canonical decimal id such as {@code "42"}; anything else is empty. */
    public static Optional<TermId> parse(CharSequence text) {
        return Ids.parse(text).map(TermId::new);
    }
}
