package org.quarkpress.core;

import java.util.Optional;

/** Identifies a revision or autosave of a post. Ids are positive and unique across the network. */
public record RevisionId(long value) {

    public RevisionId {
        Ids.requirePositive(value, "revision");
    }

    public static RevisionId of(long value) {
        return new RevisionId(value);
    }

    /** Reads a canonical decimal id such as {@code "42"}; anything else is empty. */
    public static Optional<RevisionId> parse(CharSequence text) {
        return Ids.parse(text).map(RevisionId::new);
    }
}
