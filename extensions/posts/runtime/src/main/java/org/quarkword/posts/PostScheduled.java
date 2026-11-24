package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A post was saved as scheduled (WordPress's {@code future_{type}}); {@code due} is its GMT date. */
public record PostScheduled(SiteId site, Instant at, PostSnapshot post, Instant due) implements SiteEvent {
    public PostScheduled {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(due, "due");
    }
}
