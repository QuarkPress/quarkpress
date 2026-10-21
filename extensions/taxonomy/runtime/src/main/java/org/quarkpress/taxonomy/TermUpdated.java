package org.quarkpress.taxonomy;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A term was updated (WordPress's {@code edited_term}), with the term before and after. */
public record TermUpdated(SiteId site, Instant at, TermSnapshot before, TermSnapshot after) implements SiteEvent {
    public TermUpdated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
