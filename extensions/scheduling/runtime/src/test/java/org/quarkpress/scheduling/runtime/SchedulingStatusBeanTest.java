package org.quarkpress.scheduling.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.SchedulingStatus;

class SchedulingStatusBeanTest {

    static final Instant T1 = Instant.parse("2026-01-15T12:00:00Z");
    static final Instant T2 = Instant.parse("2026-01-15T12:00:02Z");
    static final SchedulingStatus.Published P1 = new SchedulingStatus.Published(SiteId.of(1), PostId.of(7));

    @Test
    void aFreshBeanKnowsNothing() {
        SchedulingStatus.Snapshot snapshot = new SchedulingStatusBean().snapshot();
        assertThat(snapshot.lastPollStarted()).isEmpty();
        assertThat(snapshot.lastPollEnded()).isEmpty();
        assertThat(snapshot.lastBatch()).isEmpty();
        assertThat(snapshot.publishedSinceStart()).isZero();
        assertThat(snapshot.lastFailure()).isEmpty();
        assertThat(snapshot.lastHousekeeping()).isEmpty();
    }

    @Test
    void pollsAccumulateTheCountAndKeepTheLastBatch() {
        SchedulingStatusBean status = new SchedulingStatusBean();
        status.pollStarted(T1);
        status.pollEnded(T2, List.of(P1, new SchedulingStatus.Published(SiteId.of(2), PostId.of(8))));
        status.pollStarted(T2);
        status.pollEnded(T2, List.of());
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        assertThat(snapshot.lastPollStarted()).contains(T2);
        assertThat(snapshot.lastPollEnded()).contains(T2);
        assertThat(snapshot.lastBatch()).isEmpty();
        assertThat(snapshot.publishedSinceStart()).isEqualTo(2);
    }

    @Test
    void failuresAndHousekeepingAreRemembered() {
        SchedulingStatusBean status = new SchedulingStatusBean();
        SchedulingStatus.Failure failure =
                new SchedulingStatus.Failure(Optional.of(SiteId.of(1)), Optional.of(PostId.of(7)), "boom", T1);
        status.failed(failure);
        status.housekeepingRan(T2);
        assertThat(status.snapshot().lastFailure()).contains(failure);
        assertThat(status.snapshot().lastHousekeeping()).contains(T2);
    }

    @Test
    void snapshotsAreImmutableCopies() {
        SchedulingStatusBean status = new SchedulingStatusBean();
        List<SchedulingStatus.Published> batch = new ArrayList<>(List.of(P1));
        status.pollEnded(T1, batch);
        batch.clear();
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        assertThat(snapshot.lastBatch()).containsExactly(P1);
        assertThatThrownBy(() -> snapshot.lastBatch().add(P1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSnapshotBuiltDirectlyCopiesItsBatch() {
        List<SchedulingStatus.Published> batch = new ArrayList<>(List.of(P1));
        SchedulingStatus.Snapshot snapshot = new SchedulingStatus.Snapshot(
                Optional.of(T1), Optional.of(T2), batch, 1, Optional.empty(), Optional.empty());
        batch.clear();
        assertThat(snapshot.lastBatch()).containsExactly(P1);
        assertThatThrownBy(() -> snapshot.lastBatch().add(P1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
