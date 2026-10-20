package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A post was saved with a status (WordPress's {@code transition_post_status}): fired on every save, also when the
 * status did not change ({@code from} equals {@code to}); {@code from} is "new" for a created post.
 */
public record PostStatusChanged(SiteId site, Instant at, PostSnapshot post, String from, String to)
        implements SiteEvent {
    public PostStatusChanged {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
