package org.quarkpress.scheduling.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.scheduling.SchedulingStatus;

/** The jobs' status of this node, kept in memory; the jobs record into it, the Dev UI reads snapshots. */
@ApplicationScoped
public class SchedulingStatusBean implements SchedulingStatus {

    private Instant lastPollStarted;
    private Instant lastPollEnded;
    private List<Published> lastBatch = List.of();
    private long publishedSinceStart;
    private Failure lastFailure;
    private Instant lastHousekeeping;

    public synchronized void pollStarted(Instant at) {
        lastPollStarted = Objects.requireNonNull(at, "at");
    }

    /** Records the end of a poll: {@code batch} becomes the last batch and its size is added to the count. */
    public synchronized void pollEnded(Instant at, List<Published> batch) {
        lastPollEnded = Objects.requireNonNull(at, "at");
        lastBatch = List.copyOf(Objects.requireNonNull(batch, "batch"));
        publishedSinceStart += lastBatch.size();
    }

    public synchronized void failed(Failure failure) {
        lastFailure = Objects.requireNonNull(failure, "failure");
    }

    public synchronized void housekeepingRan(Instant at) {
        lastHousekeeping = Objects.requireNonNull(at, "at");
    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                Optional.ofNullable(lastPollStarted),
                Optional.ofNullable(lastPollEnded),
                lastBatch,
                publishedSinceStart,
                Optional.ofNullable(lastFailure),
                Optional.ofNullable(lastHousekeeping));
    }
}
