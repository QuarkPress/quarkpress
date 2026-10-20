package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A post was saved as published (WordPress's {@code publish_{type}}), also when it was published before. */
public record PostPublished(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public PostPublished {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
