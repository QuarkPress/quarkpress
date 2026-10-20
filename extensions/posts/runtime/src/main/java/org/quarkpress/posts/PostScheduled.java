package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A post was saved as scheduled (WordPress's {@code future_{type}}); {@code due} is its GMT date. */
public record PostScheduled(SiteId site, Instant at, PostSnapshot post, Instant due) implements SiteEvent {
    public PostScheduled {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(due, "due");
    }
}
