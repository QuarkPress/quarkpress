package org.quarkpress.taxonomy;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A term was created (WordPress's {@code created_term}). */
public record TermCreated(SiteId site, Instant at, TermSnapshot term) implements SiteEvent {
    public TermCreated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(term, "term");
    }
}
