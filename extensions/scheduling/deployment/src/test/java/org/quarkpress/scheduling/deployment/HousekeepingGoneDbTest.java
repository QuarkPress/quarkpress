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
import org.quarkpress.scheduling.runtime.Housekeeping;

/**
 * A candidate that changed between its selection and its own delete, as a concurrent delete, restore or edit would
 * make it: a gone row is skipped quietly, a restored or edited one is left alone; none is a failure.
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
            return page;
        }

        @Override
        public List<PostId> autoDraftsModifiedBefore(SiteId site, Instant before, long afterId, int limit) {
            List<PostId> page = super.autoDraftsModifiedBefore(site, before, afterId, limit);
            PostId edited = promote;
            if (edited != null && page.contains(edited)) {
                Jdbc.update(dataSource, "update qp_post set status = 'draft' where id = ?", edited.value());
            }
            return page;
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
}
