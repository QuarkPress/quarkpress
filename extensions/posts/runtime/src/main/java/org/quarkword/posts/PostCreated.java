package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A post was created (WordPress's {@code wp_insert_post} for a new post); fired after {@link PostSaved}. */
public record PostCreated(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public PostCreated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
