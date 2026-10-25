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
import org.quarkpress.posts.PostDeleted;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.Housekeeping;

/** The housekeeping job, called directly: what it purges, in what order, what it leaves alone, what it records. */
class HousekeepingDbTest {

    /**
     * A singleton, not application scoped: the injected instance is the bean itself, so the test can set its fields.
     */
    @Singleton
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        /** The title of the post whose deletion the observer refuses; null refuses none. */
        volatile String refuse;

        List<String> seen() {
            return seen;
        }

        void clear() {
            seen.clear();
            refuse = null;
        }

        void deleting(@Observes PostDeleting event) {
            if (event.post().title().equals(refuse)) {
                throw new IllegalStateException(
                        "the observer refused " + event.post().title());
            }
            seen.add("deleting " + event.post().id().value());
        }

        void deleted(@Observes PostDeleted event) {
            seen.add("deleted " + event.id().value());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(HousekeepingDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    Recorder recorder;

    @Inject
    Housekeeping housekeeping;

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
    void trashedRowsStrictlyOlderThanTheThresholdArePurgedWhateverTheirType() throws Exception {
        PostId old = TestData.trashed(dataSource, site, "post", "old", 1765886399L);
        PostId edge = TestData.trashed(dataSource, site, "post", "edge", 1765886400L);
        PostId bare = TestData.trashed(dataSource, site, "post", "bare", null);
        PostId oldPage = TestData.trashed(dataSource, site, "page", "old page", 1765886399L);
        PostId oldFile = TestData.trashed(dataSource, site, "attachment", "old file", 1765886399L);
        PostId live = TestData.post(dataSource, site, "post", "publish", "live", "live");
        TestData.setMeta(dataSource, live, "{\"_wp_trash_meta_time\":[1000000000]}");
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(3);
        assertThat(TestData.exists(dataSource, old)).isFalse();
        assertThat(TestData.exists(dataSource, oldPage)).isFalse();
        assertThat(TestData.exists(dataSource, oldFile)).isFalse();
        assertThat(TestData.status(dataSource, edge)).isEqualTo("trash");
        assertThat(TestData.status(dataSource, bare)).isEqualTo("trash");
        assertThat(TestData.status(dataSource, live)).isEqualTo("publish");
        assertThat(recorder.seen())
                .containsExactly(
                        "deleting " + old.value(), "deleted " + old.value(),
                        "deleting " + oldPage.value(), "deleted " + oldPage.value(),
                        "deleting " + oldFile.value(), "deleted " + oldFile.value());
        assertThat(housekeeping.purgeTrash(site)).isZero();
    }

    @Test
    void autoDraftsModifiedMoreThanSevenDaysAgoAreForceDeleted() throws Exception {
        PostId stale = TestData.autoDraft(dataSource, site, "post", "stale", "2026-01-08T11:59:59Z");
        PostId edge = TestData.autoDraft(dataSource, site, "post", "edge", "2026-01-08T12:00:00Z");
        PostId stalePage = TestData.autoDraft(dataSource, site, "page", "stale page", "2020-01-01T00:00:00Z");
        PostId oldDraft = TestData.post(dataSource, site, "post", "draft", "", "old draft");
        TestData.setModifiedAt(dataSource, oldDraft, "2020-01-01T00:00:00Z");
        assertThat(housekeeping.purgeAutoDrafts(site)).isEqualTo(2);
        assertThat(TestData.exists(dataSource, stale)).isFalse();
        assertThat(TestData.exists(dataSource, stalePage)).isFalse();
        assertThat(TestData.status(dataSource, edge)).isEqualTo("auto-draft");
        assertThat(TestData.status(dataSource, oldDraft)).isEqualTo("draft");
    }

    /**
     * {@code containsSubsequence} and {@code isGreaterThanOrEqualTo}: other tests' sites of this application may hold
     * purgeable rows too. The three sites are created in ascending id order.
     */
    @Test
    void aRunCoversEverySiteInOrderTrashFirstAndSkipsSitesWithSchedulingOff() throws Exception {
        SiteId a = TestData.site(dataSource);
        SiteId b = TestData.site(dataSource);
        SiteId c = TestData.site(dataSource);
        TestData.featureOff(dataSource, b, "scheduling");
        PostId aTrash = TestData.trashed(dataSource, a, "post", "a trash", 1000000000L);
        PostId aDraft = TestData.autoDraft(dataSource, a, "post", "a draft", "2020-01-01T00:00:00Z");
        PostId bTrash = TestData.trashed(dataSource, b, "post", "b trash", 1000000000L);
        PostId bDraft = TestData.autoDraft(dataSource, b, "post", "b draft", "2020-01-01T00:00:00Z");
        PostId cTrash = TestData.trashed(dataSource, c, "post", "c trash", 1000000000L);
        PostId cDraft = TestData.autoDraft(dataSource, c, "post", "c draft", "2020-01-01T00:00:00Z");
        Housekeeping.Result result = housekeeping.run();
        assertThat(result.trashPurged()).isGreaterThanOrEqualTo(2);
        assertThat(result.autoDraftsPurged()).isGreaterThanOrEqualTo(2);
        assertThat(recorder.seen().stream()
                        .filter(line -> line.startsWith("deleted "))
                        .toList())
                .containsSubsequence(
                        "deleted " + aTrash.value(), "deleted " + aDraft.value(),
                        "deleted " + cTrash.value(), "deleted " + cDraft.value());
        assertThat(TestData.status(dataSource, bTrash)).isEqualTo("trash");
        assertThat(TestData.status(dataSource, bDraft)).isEqualTo("auto-draft");
        assertThat(status.snapshot().lastHousekeeping()).contains(Instant.parse("2026-01-15T12:00:00Z"));
    }

    @Test
    void aFailingDeleteIsRecordedAndTheRunGoesOn() throws Exception {
        PostId refused = TestData.trashed(dataSource, site, "post", "refuse", 1000000000L);
        PostId fine = TestData.trashed(dataSource, site, "post", "fine", 1000000000L);
        recorder.refuse = "refuse";
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.status(dataSource, refused)).isEqualTo("trash");
        assertThat(TestData.exists(dataSource, fine)).isFalse();
        SchedulingStatus.Failure failure = status.snapshot().lastFailure().orElseThrow();
        assertThat(failure.site()).contains(site);
        assertThat(failure.post()).contains(refused);
        assertThat(failure.message()).contains("the observer refused refuse");
        recorder.refuse = null;
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
    }
}
