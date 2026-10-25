package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.PublishPoll;

/** One poll, called directly: what it publishes, what it announces, what it records and what it leaves alone. */
class PollDbTest {

    /**
     * A singleton, not application scoped: the injected instance is the bean itself, so the test can set its fields.
     */
    @Singleton
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        /** The title of the post whose publication the observer refuses; null refuses none. */
        volatile String refuse;

        List<String> seen() {
            return seen;
        }

        void clear() {
            seen.clear();
            refuse = null;
        }

        void changed(@Observes PostStatusChanged event) {
            seen.add("status " + event.from() + ">" + event.to());
        }

        void published(@Observes PostPublished event) {
            if (event.post().title().equals(refuse)) {
                throw new IllegalStateException(
                        "the observer refused " + event.post().title());
            }
            seen.add("published " + event.post().id().value());
        }

        void saved(@Observes PostSaved event) {
            seen.add("saved created=" + event.created());
        }

        void updated(@Observes PostUpdated event) {
            seen.add("updated");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(PollDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    Recorder recorder;

    @Inject
    PublishPoll poll;

    @Inject
    SchedulingStatus status;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
        recorder.clear();
    }

    @Test
    void aDuePostIsPublishedWithOnlyThePublishEventsAndRecorded() throws Exception {
        PostId due = TestData.scheduled(dataSource, site, "due", "2026-01-15T11:59:00Z");
        String modifiedBefore = Jdbc.strings(
                        dataSource,
                        "select " + TestDb.utcText("modified_at") + " from qp_post where id = ?",
                        due.value())
                .get(0);
        List<SchedulingStatus.Published> published = poll.poll();
        assertThat(published).containsExactly(new SchedulingStatus.Published(site, due));
        assertThat(TestData.status(dataSource, due)).isEqualTo("publish");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select " + TestDb.utcText("modified_at") + " from qp_post where id = ?",
                        due.value()))
                .containsExactly(modifiedBefore);
        assertThat(recorder.seen())
                .containsExactly("status future>publish", "published " + due.value(), "saved created=false");
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        assertThat(snapshot.lastPollStarted()).contains(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastPollEnded()).contains(Instant.parse("2026-01-15T12:00:00Z"));
        assertThat(snapshot.lastBatch()).containsExactly(new SchedulingStatus.Published(site, due));
        assertThat(snapshot.publishedSinceStart()).isGreaterThanOrEqualTo(1);
        assertThat(poll.poll()).isEmpty();
    }

    @Test
    void aPostDueThisSecondIsPublishedAndOneDueNextSecondWaits() throws Exception {
        PostId now = TestData.scheduled(dataSource, site, "now", "2026-01-15T12:00:00Z");
        PostId later = TestData.scheduled(dataSource, site, "later", "2026-01-15T12:00:01Z");
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, now));
        assertThat(TestData.status(dataSource, later)).isEqualTo("future");
        clock.set(Instant.parse("2026-01-15T12:00:01Z"));
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, later));
    }

    @Test
    void aSiteWithSchedulingOffKeepsItsPostsUntilItIsOnAgain() throws Exception {
        PostId held = TestData.scheduled(dataSource, site, "held", "2026-01-15T11:00:00Z");
        TestData.featureOff(dataSource, site, "scheduling");
        assertThat(poll.poll()).isEmpty();
        assertThat(TestData.status(dataSource, held)).isEqualTo("future");
        assertThat(recorder.seen()).isEmpty();
        clock.set(Instant.parse("2026-01-20T12:00:00Z"));
        TestData.featureOn(dataSource, site, "scheduling");
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, held));
        assertThat(TestData.status(dataSource, held)).isEqualTo("publish");
    }

    /** A failure in the middle of a site's batch neither undoes the post before it nor stops the one after it. */
    @Test
    void aFailingPostIsLoggedRecordedAndRetried() throws Exception {
        PostId early = TestData.scheduled(dataSource, site, "early", "2026-01-15T10:30:00Z");
        PostId refused = TestData.scheduled(dataSource, site, "refuse", "2026-01-15T11:00:00Z");
        PostId fine = TestData.scheduled(dataSource, site, "fine", "2026-01-15T11:30:00Z");
        recorder.refuse = "refuse";
        assertThat(poll.poll())
                .containsExactly(
                        new SchedulingStatus.Published(site, early), new SchedulingStatus.Published(site, fine));
        assertThat(TestData.status(dataSource, early)).isEqualTo("publish");
        assertThat(TestData.status(dataSource, refused)).isEqualTo("future");
        assertThat(TestData.status(dataSource, fine)).isEqualTo("publish");
        SchedulingStatus.Failure failure = status.snapshot().lastFailure().orElseThrow();
        assertThat(failure.site()).contains(site);
        assertThat(failure.post()).contains(refused);
        assertThat(failure.message()).contains("the observer refused refuse");
        assertThat(failure.at()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        recorder.refuse = null;
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, refused));
        assertThat(TestData.status(dataSource, refused)).isEqualTo("publish");
    }

    @Test
    void postsOfOtherStatusesAndTheirDatesAreLeftAlone() throws Exception {
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", "draft");
        PostId trash = TestData.trashed(dataSource, site, "post", "binned", 1000000000L);
        PostId live = TestData.post(dataSource, site, "post", "publish", "live", "live");
        assertThat(poll.poll()).isEmpty();
        assertThat(TestData.status(dataSource, draft)).isEqualTo("draft");
        assertThat(TestData.status(dataSource, trash)).isEqualTo("trash");
        assertThat(TestData.status(dataSource, live)).isEqualTo("publish");
        assertThat(recorder.seen()).isEmpty();
    }
}
