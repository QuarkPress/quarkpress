package org.quarkpress.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A site and all its rows were deleted, inside the deleting transaction (WordPress's {@code wp_delete_site}).
 * {@code deleted} is the site's last record.
 */
public record SiteDeleted(SiteId site, Instant at, SiteInfo deleted) implements SiteEvent {

    public SiteDeleted {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(deleted, "deleted");
        SiteEvents.requireSame(site, deleted);
    }
}
