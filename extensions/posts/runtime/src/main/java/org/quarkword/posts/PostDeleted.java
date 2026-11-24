package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A post was deleted for good (WordPress's {@code deleted_post}), attachments included. */
public record PostDeleted(SiteId site, Instant at, PostId id, String type) implements SiteEvent {
    public PostDeleted {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }
}
