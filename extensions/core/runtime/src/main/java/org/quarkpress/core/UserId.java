package org.quarkpress.core;

import java.util.Optional;

/** Identifies a user profile of the network. Ids are positive. */
public record UserId(long value) {

    public UserId {
        Ids.requirePositive(value, "user");
    }

    public static UserId of(long value) {
        return new UserId(value);
    }

    /** Reads a canonical decimal id such as {@code "42"}; anything else is empty. */
    public static Optional<UserId> parse(CharSequence text) {
        return Ids.parse(text).map(UserId::new);
    }
}
