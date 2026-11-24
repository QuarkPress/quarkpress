package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A post was saved, created or updated (WordPress's {@code save_post}). */
public record PostSaved(SiteId site, Instant at, PostSnapshot post, boolean created) implements SiteEvent {
    public PostSaved {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
