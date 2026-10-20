package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A post was updated (WordPress's {@code post_updated}): the stored post before and after the update. */
public record PostUpdated(SiteId site, Instant at, PostSnapshot before, PostSnapshot after) implements SiteEvent {
    public PostUpdated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
