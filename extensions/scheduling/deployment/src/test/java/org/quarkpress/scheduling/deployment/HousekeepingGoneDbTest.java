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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.DuePosts;
import org.quarkpress.scheduling.runtime.Housekeeping;

/**
 * A candidate that changed between its selection and its own delete, as a concurrent delete, restore, re-trash or
 * edit would make it: a gone row is skipped quietly, a restored, re-trashed or edited one is left alone; none is a
 * failure.
 */
class HousekeepingGoneDbTest {

    /**
     * The housekeeping reads, with one selected row changed (past the service) right after the page that names it
     * was read; a singleton so the test sets its fields on the bean itself.
     */
    @Alternative
    @Priority(1)
    @Singleton
    static class MeddlingDuePosts extends DuePosts {

        @Inject
        @DataSource("quarkpress")
        AgroalDataSource dataSource;

        /** The trashed post removed once it has been selected; null removes none. */
        volatile PostId vanish;

        /** The trashed post published again, its trash meta gone, once it has been selected; null restores none. */
        volatile PostId restore;

        /** The auto-draft turned into a draft once it has been selected; null turns none. */
        volatile PostId promote;

        /**
         * The trashed post trashed again once it has been selected: still in the trash, with its trash time set to
         * {@link #retrashTime} (seconds); null re-trashes none.
         */
        volatile PostId retrash;

        volatile long retrashTime;

        /**
         * The auto-draft saved again once it has been selected, its modification time set to {@link #touchedAt} (an
         * ISO instant); null touches none.
         */
        volatile PostId touch;

        volatile String touchedAt;

        /**
         * The post removed right after the delete's own re-read found it still purgeable, so the posts module's own
         * lookup no longer finds it; null removes none. The delete runs on a thread, and so a connection, of its own:
         * on the job's thread it would join the job's transaction and be undone with it.
         */
        volatile PostId vanishLate;

        @Override
        public List<Trashed> trashedAfter(SiteId site, long afterId, int limit) {
            List<Trashed> page = super.trashedAfter(site, afterId, limit);
            PostId gone = vanish;
            if (gone != null && page.stream().anyMatch(row -> row.id().equals(gone))) {
                Jdbc.update(dataSource, "delete from qp_post where id = ?", gone.value());
            }
            PostId back = restore;
            if (back != null && page.stream().anyMatch(row -> row.id().equals(back))) {
                Jdbc.update(
                        dataSource,
                        "update qp_post set status = 'publish', meta = " + TestDb.json() + " where id = ?",
                        "{}",
                        back.value());
            }
            PostId again = retrash;
            if (again != null && page.stream().anyMatch(row -> row.id().equals(again))) {
                TestData.setMeta(
                        dataSource,
                        again,
                        "{\"_wp_trash_meta_status\":[\"publish\"],\"_wp_trash_meta_time\":[" + retrashTime + "]}");
            }
            return page;
        }

        @Override
        public List<PostId> autoDraftsModifiedBefore(SiteId site, Instant before, long afterId, int limit) {
            List<PostId> page = super.autoDraftsModifiedBefore(site, before, afterId, limit);
            PostId edited = promote;
            if (edited != null && page.contains(edited)) {
                Jdbc.update(dataSource, "update qp_post set status = 'draft' where id = ?", edited.value());
            }
            PostId saved = touch;
            if (saved != null && page.contains(saved)) {
                TestData.setModifiedAt(dataSource, saved, touchedAt);
            }
            return page;
        }

        @Override
        public Optional<PostEntity> post(SiteId site, PostId id) {
            Optional<PostEntity> found = super.post(site, id);
            if (found.isPresent() && id.equals(vanishLate)) {
                Thread other = Thread.ofVirtual()
                        .unstarted(() -> Jdbc.update(dataSource, "delete from qp_post where id = ?", id.value()));
                other.start();
                try {
                    other.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return found;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(HousekeepingGoneDbTest.class, MeddlingDuePosts.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    MeddlingDuePosts meddling;

    @Inject
    Housekeeping housekeeping;

    @Inject
    SchedulingStatus status;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
        meddling.vanish = null;
        meddling.restore = null;
        meddling.promote = null;
        meddling.retrash = null;
        meddling.touch = null;
        meddling.vanishLate = null;
    }

    @Test
    void aCandidateGoneBeforeItsOwnDeleteIsSkippedWithoutAFailure() throws Exception {
        PostId gone = TestData.trashed(dataSource, site, "post", "gone", 1000000000L);
        PostId fine = TestData.trashed(dataSource, site, "post", "fine", 1000000000L);
        meddling.vanish = gone;
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.exists(dataSource, gone)).isFalse();
        assertThat(TestData.exists(dataSource, fine)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeTrash(site)).isZero();
    }

    @Test
    void aCandidateRestoredBeforeItsOwnDeleteStaysAsRestored() throws Exception {
        PostId restored = TestData.trashed(dataSource, site, "post", "restored", 1000000000L);
        PostId fine = TestData.trashed(dataSource, site, "attachment", "fine", 1000000000L);
        meddling.restore = restored;
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.status(dataSource, restored)).isEqualTo("publish");
        assertThat(TestData.exists(dataSource, fine)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeTrash(site)).isZero();
        assertThat(TestData.status(dataSource, restored)).isEqualTo("publish");
    }

    @Test
    void anAutoDraftTurnedIntoADraftBeforeItsOwnDeleteStaysADraft() throws Exception {
        PostId edited = TestData.autoDraft(dataSource, site, "post", "edited", "2020-01-01T00:00:00Z");
        PostId stale = TestData.autoDraft(dataSource, site, "page", "stale", "2020-01-01T00:00:00Z");
        meddling.promote = edited;
        assertThat(housekeeping.purgeAutoDrafts(site)).isEqualTo(1);
        assertThat(TestData.status(dataSource, edited)).isEqualTo("draft");
        assertThat(TestData.exists(dataSource, stale)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeAutoDrafts(site)).isZero();
        assertThat(TestData.status(dataSource, edited)).isEqualTo("draft");
    }

    /** Still in the trash, but with a trash time of now: only the re-read of the trash time keeps it. */
    @Test
    void aCandidateTrashedAgainWithAFreshTrashTimeBeforeItsOwnDeleteStaysInTheTrash() throws Exception {
        PostId again = TestData.trashed(dataSource, site, "post", "again", 1000000000L);
        PostId fine = TestData.trashed(dataSource, site, "post", "fine", 1000000000L);
        meddling.retrash = again;
        meddling.retrashTime = clock.now().getEpochSecond();
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.status(dataSource, again)).isEqualTo("trash");
        assertThat(TestData.exists(dataSource, fine)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeTrash(site)).isZero();
        assertThat(TestData.status(dataSource, again)).isEqualTo("trash");
    }

    /** Still an auto-draft, but modified just now: only the re-read of the modification time keeps it. */
    @Test
    void anAutoDraftModifiedJustNowBeforeItsOwnDeleteStaysAnAutoDraft() throws Exception {
        PostId saved = TestData.autoDraft(dataSource, site, "post", "saved", "2020-01-01T00:00:00Z");
        PostId stale = TestData.autoDraft(dataSource, site, "page", "stale", "2020-01-01T00:00:00Z");
        meddling.touch = saved;
        meddling.touchedAt = clock.now().toString();
        assertThat(housekeeping.purgeAutoDrafts(site)).isEqualTo(1);
        assertThat(TestData.status(dataSource, saved)).isEqualTo("auto-draft");
        assertThat(TestData.exists(dataSource, stale)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeAutoDrafts(site)).isZero();
        assertThat(TestData.status(dataSource, saved)).isEqualTo("auto-draft");
    }

    /**
     * Gone after the delete's own re-read found it, so the posts module itself refuses the delete as an invalid post:
     * skipped quietly, not a failure.
     */
    @Test
    void aCandidateGoneAfterItsReReadIsSkippedWithoutAFailure() throws Exception {
        PostId gone = TestData.trashed(dataSource, site, "post", "gone", 1000000000L);
        PostId fine = TestData.trashed(dataSource, site, "post", "fine", 1000000000L);
        meddling.vanishLate = gone;
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.exists(dataSource, gone)).isFalse();
        assertThat(TestData.exists(dataSource, fine)).isFalse();
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeTrash(site)).isZero();
    }
}
