package org.quarkword.posts;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/**
 * A post was moved to the trash (WordPress's {@code trashed_post}), attachments included; {@code post} is the trashed
 * post, whose meta {@code _wp_trash_meta_status} holds the status it had.
 */
public record PostTrashed(SiteId site, Instant at, PostSnapshot post) implements SiteEvent {
    public PostTrashed {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
    }
}
