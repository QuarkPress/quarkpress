package org.quarkpress.media;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * An upload was stored: its file is in the storage and its attachment exists, its sizes are still to be made. Fired to
 * synchronous observers inside the storing transaction and, once it has committed, to asynchronous ones.
 *
 * @param site the attachment's site
 * @param at when it was stored
 * @param media the attachment as stored
 */
public record MediaStored(SiteId site, Instant at, MediaSnapshot media) implements SiteEvent {

    public MediaStored {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(media, "media");
    }
}
