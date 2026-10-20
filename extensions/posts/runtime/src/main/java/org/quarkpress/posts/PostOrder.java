package org.quarkpress.posts;

import java.util.Objects;

/** One ordering key and its direction. */
public record PostOrder(OrderKey key, boolean ascending) {
    public PostOrder {
        Objects.requireNonNull(key, "key");
    }
}
