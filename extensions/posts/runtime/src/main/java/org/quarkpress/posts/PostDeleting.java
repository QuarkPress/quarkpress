package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A post is about to be deleted for good (WordPress's {@code before_delete_post}, or {@code delete_attachment} for an
 * attachment), inside the deleting transaction and before anything is removed or changed. Observers that keep data
 * about the post clean it up now; what they write commits or rolls back with the delete.
 */
public record PostDeleting(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public PostDeleting {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
