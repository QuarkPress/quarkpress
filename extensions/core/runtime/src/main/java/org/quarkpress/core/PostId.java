package org.quarkpress.core;

import java.util.Optional;

/** Identifies a post (any post type). Ids are positive and unique across the network. */
public record PostId(long value) {

    public PostId {
        Ids.requirePositive(value, "post");
    }

    public static PostId of(long value) {
        return new PostId(value);
    }

    /** Reads a canonical decimal id such as {@code "42"}; anything else is empty. */
    public static Optional<PostId> parse(CharSequence text) {
        return Ids.parse(text).map(PostId::new);
    }
}
