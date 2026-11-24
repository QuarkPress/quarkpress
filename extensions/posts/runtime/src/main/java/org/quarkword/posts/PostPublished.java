package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A post was saved as published (WordPress's {@code publish_{type}}), also when it was published before. */
public record PostPublished(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public PostPublished {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
