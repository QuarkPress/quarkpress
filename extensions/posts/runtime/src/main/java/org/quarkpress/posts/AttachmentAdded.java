package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * An attachment was created (WordPress's {@code add_attachment}). Creating an attachment fires this event alone: no
 * status transition, {@link PostSaved} or {@link PostCreated}, so modules that follow post saves, such as revisions,
 * leave attachments alone.
 */
public record AttachmentAdded(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public AttachmentAdded {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
