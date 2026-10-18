package org.quarkpress.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A site was created, together with its default options, inside the transaction that created it (WordPress's
 * {@code wp_initialize_site}). Observers that add rows for the new site write them in that transaction, naming the
 * site explicitly.
 */
public record SiteCreated(SiteId site, Instant at, SiteInfo created) implements SiteEvent {

    public SiteCreated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(created, "created");
        SiteEvents.requireSame(site, created);
    }
}
