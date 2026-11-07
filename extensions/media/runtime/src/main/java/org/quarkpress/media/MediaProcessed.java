package org.quarkpress.media;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A processing run of an attachment ended: its main file, its sizes and its metadata are as {@code media} shows them.
 * Fired once per run, after its last save, to synchronous observers inside that save's transaction and, once it has
 * committed, to asynchronous ones. A post-process that finds nothing to make fires nothing.
 *
 * @param site the attachment's site
 * @param at when the run ended
 * @param media the attachment as the run left it
 */
public record MediaProcessed(SiteId site, Instant at, MediaSnapshot media) implements SiteEvent {

    public MediaProcessed {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(media, "media");
    }
}
