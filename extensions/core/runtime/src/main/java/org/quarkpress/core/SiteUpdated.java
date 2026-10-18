package org.quarkpress.core;

import java.time.Instant;
import java.util.Objects;

/** A site's record changed: its domain, path, name, public value or flags (WordPress's {@code wp_update_site}). */
public record SiteUpdated(SiteId site, Instant at, SiteInfo before, SiteInfo after) implements SiteEvent {

    public SiteUpdated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        SiteEvents.requireSame(site, before);
        SiteEvents.requireSame(site, after);
    }
}
