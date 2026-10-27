package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.devui.runtime.DevViews.FailureView;
import org.quarkpress.devui.runtime.DevViews.PublishedView;
import org.quarkpress.devui.runtime.DevViews.SchedulerView;
import org.quarkpress.devui.runtime.DevViews.UpcomingView;
import org.quarkpress.scheduling.ScheduledPosts;
import org.quarkpress.scheduling.SchedulingStatus;

/** The Dev UI's JSON-RPC method of the scheduling module: the next scheduled posts and this node's job status. */
public class SchedulerDevJsonRpc {

    /** How many scheduled posts the scheduler page lists. */
    static final int UPCOMING = 20;

    @Inject
    DevCalls calls;

    @Inject
    ScheduledPosts scheduled;

    @Inject
    SchedulingStatus status;

    /**
     * The next {@value #UPCOMING} scheduled posts of every site, soonest first and overdue ones included, and this
     * node's scheduling status since it started.
     */
    public SchedulerView scheduler() {
        return calls.asSystem(() -> {
            List<UpcomingView> upcoming = new ArrayList<>();
            for (ScheduledPosts.Upcoming post : scheduled.next(UPCOMING)) {
                upcoming.add(new UpcomingView(
                        post.site().value(),
                        post.post().value(),
                        post.type(),
                        post.title(),
                        post.publishAt().toString()));
            }
            SchedulingStatus.Snapshot snapshot = status.snapshot();
            List<PublishedView> lastBatch = new ArrayList<>();
            for (SchedulingStatus.Published published : snapshot.lastBatch()) {
                lastBatch.add(new PublishedView(
                        published.site().value(), published.post().value()));
            }
            return new SchedulerView(
                    upcoming,
                    text(snapshot.lastPollStarted()),
                    text(snapshot.lastPollEnded()),
                    lastBatch,
                    snapshot.publishedSinceStart(),
                    snapshot.lastFailure().map(SchedulerDevJsonRpc::view).orElse(null),
                    text(snapshot.lastHousekeeping()));
        });
    }

    private static FailureView view(SchedulingStatus.Failure failure) {
        return new FailureView(
                failure.site().map(SiteId::value).orElse(null),
                failure.post().map(PostId::value).orElse(null),
                failure.message(),
                failure.at().toString());
    }

    private static String text(Optional<Instant> instant) {
        return instant.map(Instant::toString).orElse(null);
    }
}
