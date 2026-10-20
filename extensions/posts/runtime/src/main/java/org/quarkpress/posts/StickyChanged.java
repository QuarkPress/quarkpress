package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A post was stuck or unstuck (WordPress's {@code post_stuck} and {@code post_unstuck}), only when that changed. */
public record StickyChanged(SiteId site, Instant at, PostId id, boolean sticky) implements SiteEvent {
    public StickyChanged {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(id, "id");
    }
}
