package org.quarkpress.posts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A post left the trash (WordPress's {@code untrashed_post}), attachments included; {@code post} is the restored post
 * (a draft, or inherit for an attachment). {@code previousStatus} is the status it had before it was trashed, as
 * recorded when it was trashed; empty when none was recorded, or the recorded one is empty or "0".
 */
public record PostRestored(SiteId site, Instant at, PostSnapshot post, Optional<String> previousStatus)
        implements SiteEvent {
    public PostRestored {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(previousStatus, "previousStatus");
    }
}
