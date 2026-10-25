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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.DuePosts;
import org.quarkpress.scheduling.runtime.PublishPoll;

/** The poll's reads failing: the site list, or one site's selection; what is recorded and what goes on. */
class PollFailuresDbTest {

    /** The poll's reads, made to fail on demand; a singleton so the test sets its fields on the bean itself. */
    @Alternative
    @Priority(1)
    @Singleton
    static class FailingDuePosts extends DuePosts {
        volatile boolean failSites;
        volatile SiteId failSite;

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
            return super.duePosts(site, now, limit);
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
}
