package org.quarkpress.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;

/**
 * The state of this node's scheduling jobs since it started, in memory, for the Dev UI: when the last poll ran and
 * what it published, how many posts were published in all, the last failure and the last housekeeping run. Nothing
 * is persisted or shared between nodes; a restart starts from nothing.
 */
public interface SchedulingStatus {

    /** A post the polling job published. */
    record Published(SiteId site, PostId post) {
        public Published {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(post, "post");
        }
    }

    /** A job's failure: the site and post it concerned when it concerned one, its message and when it happened. */
    record Failure(Optional<SiteId> site, Optional<PostId> post, String message, Instant at) {
        public Failure {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(post, "post");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(at, "at");
        }
    }

    /** An immutable reading of the status; {@code lastBatch} is the last poll's published posts, in order. */
    record Snapshot(
            Optional<Instant> lastPollStarted,
            Optional<Instant> lastPollEnded,
            List<Published> lastBatch,
            long publishedSinceStart,
            Optional<Failure> lastFailure,
            Optional<Instant> lastHousekeeping) {
        public Snapshot {
            Objects.requireNonNull(lastPollStarted, "lastPollStarted");
            Objects.requireNonNull(lastPollEnded, "lastPollEnded");
            lastBatch = List.copyOf(Objects.requireNonNull(lastBatch, "lastBatch"));
            Objects.requireNonNull(lastFailure, "lastFailure");
            Objects.requireNonNull(lastHousekeeping, "lastHousekeeping");
        }
    }

    Snapshot snapshot();
}
