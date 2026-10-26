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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
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
 * failure. A change racing the delete itself, after its re-read, either lands first and keeps the post or waits for
 * the delete's lock and changes nothing: never the restored post trashed again, never the saved draft deleted.
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
         * The trashed post published again, its trash meta gone, from a connection of its own, right after the
         * delete's own re-read found it still purgeable; null restores none. The update runs on a thread of its own
         * (on the job's thread it would join the job's transaction) and is waited for at most {@link #LATE_WAIT}:
         * under the lock the re-read holds it waits for the delete's transaction to end.
         */
        volatile PostId restoreLate;

        /** The auto-draft saved as a draft the same way, right after the delete's own re-read; null saves none. */
        volatile PostId promoteLate;

        /** The last late update: the rows it changed, once it has run. */
        volatile CompletableFuture<Integer> lateUpdate;

        static final long LATE_WAIT = 2;

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
            if (found.isPresent() && id.equals(restoreLate)) {
                late(() -> Jdbc.update(
                        dataSource,
                        "update qp_post set status = 'publish', meta = " + TestDb.json() + " where id = ?",
                        "{}",
                        id.value()));
            }
            if (found.isPresent() && id.equals(promoteLate)) {
                late(() -> Jdbc.update(dataSource, "update qp_post set status = 'draft' where id = ?", id.value()));
            }
            return found;
        }

        /** Starts {@code update} on a thread of its own and gives it {@link #LATE_WAIT} seconds to land. */
        private void late(IntSupplier update) {
            CompletableFuture<Integer> result = new CompletableFuture<>();
            lateUpdate = result;
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(update.getAsInt());
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
            try {
                result.get(LATE_WAIT, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                // the update waits for a lock this transaction holds: the job goes on and ends it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
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
        meddling.restoreLate = null;
        meddling.promoteLate = null;
        meddling.lateUpdate = null;
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
     * Restored from a connection of its own right after the delete's own re-read: the re-read holds the site's post
     * lock and the row, so the two are serial. Either the restore landed first and the post stays published, or the
     * delete went first and the restore changed no row; never the restore applied and the post trashed again, as a
     * re-read outside the lock would let happen.
     */
    @Test
    void aRestoreRacingTheDeleteAfterItsReReadEitherKeepsThePostOrChangesNothing() throws Exception {
        PostId restored = TestData.trashed(dataSource, site, "post", "restored", 1000000000L);
        meddling.restoreLate = restored;
        int purged = housekeeping.purgeTrash(site);
        int changed = meddling.lateUpdate.get(30, TimeUnit.SECONDS);
        if (changed == 0) {
            assertThat(purged).as("the delete went first: the post is gone").isEqualTo(1);
            assertThat(TestData.exists(dataSource, restored)).isFalse();
        } else {
            assertThat(purged).as("the restore went first: the post is kept").isZero();
            assertThat(TestData.status(dataSource, restored)).isEqualTo("publish");
        }
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeTrash(site)).isZero();
        assertThat(TestData.status(dataSource, restored)).isEqualTo(changed == 0 ? "" : "publish");
    }

    /**
     * Saved as a draft from a connection of its own right after the delete's own re-read, as the editor's first save
     * of a stale auto-draft would: either the save landed first and the draft is kept, or the delete went first and
     * the save changed no row; never the save applied and the draft deleted for good.
     */
    @Test
    void aDraftSaveRacingTheDeleteAfterItsReReadEitherKeepsTheDraftOrChangesNothing() throws Exception {
        PostId edited = TestData.autoDraft(dataSource, site, "post", "edited", "2020-01-01T00:00:00Z");
        meddling.promoteLate = edited;
        int purged = housekeeping.purgeAutoDrafts(site);
        int changed = meddling.lateUpdate.get(30, TimeUnit.SECONDS);
        if (changed == 0) {
            assertThat(purged)
                    .as("the delete went first: the auto-draft is gone")
                    .isEqualTo(1);
            assertThat(TestData.exists(dataSource, edited)).isFalse();
        } else {
            assertThat(purged).as("the save went first: the draft is kept").isZero();
            assertThat(TestData.status(dataSource, edited)).isEqualTo("draft");
        }
        assertThat(status.snapshot().lastFailure()).isEmpty();
        assertThat(housekeeping.purgeAutoDrafts(site)).isZero();
        assertThat(TestData.status(dataSource, edited)).isEqualTo(changed == 0 ? "" : "draft");
    }
}
