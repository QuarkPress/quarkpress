package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/**
 * An attachment was updated (WordPress's {@code edit_attachment} and {@code attachment_updated}): the stored attachment
 * before and after the update. Updating an attachment fires this event alone: no status transition,
 * {@link PostUpdated} or {@link PostSaved}.
 */
public record AttachmentEdited(SiteId site, Instant at, PostSnapshot before, PostSnapshot after) implements SiteEvent {
    public AttachmentEdited {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
