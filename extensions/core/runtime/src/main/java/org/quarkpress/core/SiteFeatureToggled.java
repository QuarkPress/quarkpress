package org.quarkpress.core;

import java.time.Instant;
import java.util.Objects;

/** The effective state of a per-site feature changed for one site. */
public record SiteFeatureToggled(SiteId site, Instant at, String key, boolean enabled) implements SiteEvent {

    public SiteFeatureToggled {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(key, "key");
    }
}
