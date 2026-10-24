package org.quarkpress.revisions;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A revision was removed (WordPress's {@code wp_delete_post_revision}): by limit trimming, by {@code Revisions.delete},
 * or because its post is being deleted.
 */
public record RevisionDeleted(SiteId site, Instant at, RevisionSnapshot revision) implements SiteEvent {
    public RevisionDeleted {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(revision, "revision");
    }
}
