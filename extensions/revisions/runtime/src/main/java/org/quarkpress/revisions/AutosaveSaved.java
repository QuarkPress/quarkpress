package org.quarkpress.revisions;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * An autosave was written: created ({@code updated} false) or updated. Not fired when an identical autosave is
 * returned untouched.
 */
public record AutosaveSaved(SiteId site, Instant at, RevisionSnapshot autosave, boolean updated) implements SiteEvent {
    public AutosaveSaved {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(autosave, "autosave");
    }
}
