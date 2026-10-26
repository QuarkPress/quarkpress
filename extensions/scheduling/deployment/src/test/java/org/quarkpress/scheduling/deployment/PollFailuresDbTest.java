package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.DuePosts;
import org.quarkpress.scheduling.runtime.PublishPoll;

/**
 * The poll's reads failing: the site list, or one site's selection; what is recorded and what goes on. And a
 * selection naming a post that is gone by the time it is published: skipped, not a failure.
 */
class PollFailuresDbTest {

    /**
     * The poll's reads, made to fail on demand, or to name a post that no longer exists; a singleton so the test sets
     * its fields on the bean itself.
     */
    @Alternative
    @Priority(1)
    @Singleton
    static class FailingDuePosts extends DuePosts {
        volatile boolean failSites;
        volatile SiteId failSite;

        /** A post put first in every site's selection, as one deleted after its selection would be; null adds none. */
        volatile PostId ghost;

        @Override
        public List<SiteId> sitesWithDuePosts(Instant now) {
            if (failSites) {
                throw new IllegalStateException("no site list today");
            }
            return super.sitesWithDuePosts(now);
        }

        @Override
        public List<PostId> duePosts(SiteId site, Instant now, int limit) {
            if (site.equals(failSite)) {
                throw new IllegalStateException("no selection for site " + site.value());
            }
            List<PostId> due = super.duePosts(site, now, limit);
            PostId gone = ghost;
            if (gone == null) {
                return due;
            }
            List<PostId> withGhost = new ArrayList<>();
            withGhost.add(gone);
            withGhost.addAll(due);
            return withGhost;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(PollFailuresDbTest.class, FailingDuePosts.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    FailingDuePosts failing;

    @Inject
    PublishPoll poll;

    @Inject
    SchedulingStatus status;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        failing.failSites = false;
        failing.failSite = null;
        failing.ghost = null;
    }

    @Test
    void aFailingSiteListIsRecordedWithoutSiteOrPostAndThePollEndsEmpty() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId due = TestData.scheduled(dataSource, site, "due", "2026-01-15T11:00:00Z");
        failing.failSites = true;
        assertThat(poll.poll()).isEmpty();
        assertThat(TestData.status(dataSource, due)).isEqualTo("future");
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        SchedulingStatus.Failure failure = snapshot.lastFailure().orElseThrow();
        assertThat(failure.site()).isEmpty();
        assertThat(failure.post()).isEmpty();
        assertThat(failure.message()).isEqualTo("no site list today");
        assertThat(failure.at()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastPollStarted()).contains(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastPollEnded()).contains(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastBatch()).isEmpty();
        failing.failSites = false;
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, due));
    }

    @Test
    void aFailingSiteSelectionIsRecordedWithTheSiteAndThePollGoesOnWithTheNextSite() throws Exception {
        SiteId broken = TestData.site(dataSource);
        SiteId next = TestData.site(dataSource);
        PostId held = TestData.scheduled(dataSource, broken, "held", "2026-01-15T11:00:00Z");
        PostId due = TestData.scheduled(dataSource, next, "due", "2026-01-15T11:00:00Z");
        failing.failSite = broken;
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(next, due));
        assertThat(TestData.status(dataSource, held)).isEqualTo("future");
        assertThat(TestData.status(dataSource, due)).isEqualTo("publish");
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        SchedulingStatus.Failure failure = snapshot.lastFailure().orElseThrow();
        assertThat(failure.site()).contains(broken);
        assertThat(failure.post()).isEmpty();
        assertThat(failure.message()).isEqualTo("no selection for site " + broken.value());
        assertThat(failure.at()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastPollEnded()).contains(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastBatch()).containsExactly(new SchedulingStatus.Published(next, due));
        failing.failSite = null;
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(broken, held));
    }

    /**
     * A selected post deleted before its own publication is refused by the posts module as an invalid post: the poll
     * skips it at DEBUG, records no failure for it and publishes the next post of the site.
     */
    @Test
    void aSelectedPostGoneBeforeItsPublicationIsSkippedWithoutAFailureAndTheNextOneIsPublished() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId gone = TestData.scheduled(dataSource, site, "gone", "2026-01-15T11:00:00Z");
        PostId due = TestData.scheduled(dataSource, site, "due", "2026-01-15T11:00:00Z");
        Jdbc.update(dataSource, "delete from qp_post where id = ?", gone.value());
        failing.ghost = gone;
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, due));
        assertThat(TestData.status(dataSource, due)).isEqualTo("publish");
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        // other tests of this application record failures without a post; a failure for the gone post is this one's
        assertThat(snapshot.lastFailure().flatMap(SchedulingStatus.Failure::post))
                .as("no failure recorded for the gone post")
                .isNotEqualTo(Optional.of(gone));
        assertThat(snapshot.lastBatch()).containsExactly(new SchedulingStatus.Published(site, due));
    }
}
