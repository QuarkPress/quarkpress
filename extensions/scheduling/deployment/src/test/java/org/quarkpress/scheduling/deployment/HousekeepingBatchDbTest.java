package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.runtime.Housekeeping;

/** With pages of two, a purge walks every page of a site, past the rows it leaves alone. */
class HousekeepingBatchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(HousekeepingBatchDbTest.class)
            .overrideConfigKey("quarkpress.scheduling.batch-size", "2");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    Housekeeping housekeeping;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
    }

    @Test
    void theTrashPurgeWalksEveryPagePastTheRowsItKeeps() throws Exception {
        PostId old1 = TestData.trashed(dataSource, site, "post", "old 1", 1000000000L);
        PostId bare = TestData.trashed(dataSource, site, "post", "bare", null);
        PostId old2 = TestData.trashed(dataSource, site, "page", "old 2", 1000000000L);
        PostId recent = TestData.trashed(dataSource, site, "post", "recent", 1768478399L);
        PostId old3 = TestData.trashed(dataSource, site, "attachment", "old 3", 1000000000L);
        PostId old4 = TestData.trashed(dataSource, site, "post", "old 4", 1765886399L);
        PostId edge = TestData.trashed(dataSource, site, "post", "edge", 1765886400L);
        PostId old5 = TestData.trashed(dataSource, site, "post", "old 5", 1000000000L);
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(5);
        for (PostId gone : List.of(old1, old2, old3, old4, old5)) {
            assertThat(TestData.exists(dataSource, gone))
                    .as("purged " + gone.value())
                    .isFalse();
        }
        for (PostId kept : List.of(bare, recent, edge)) {
            assertThat(TestData.status(dataSource, kept))
                    .as("kept " + kept.value())
                    .isEqualTo("trash");
        }
        assertThat(housekeeping.purgeTrash(site)).isZero();
    }

    @Test
    void theAutoDraftPurgeWalksEveryPagePastTheRowsItKeeps() throws Exception {
        PostId stale1 = TestData.autoDraft(dataSource, site, "post", "stale 1", "2020-01-01T00:00:00Z");
        PostId stale2 = TestData.autoDraft(dataSource, site, "page", "stale 2", "2026-01-08T11:59:59Z");
        PostId fresh = TestData.autoDraft(dataSource, site, "post", "fresh", "2026-01-14T00:00:00Z");
        PostId stale3 = TestData.autoDraft(dataSource, site, "post", "stale 3", "2020-01-01T00:00:00Z");
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", "draft");
        TestData.setModifiedAt(dataSource, draft, "2020-01-01T00:00:00Z");
        PostId stale4 = TestData.autoDraft(dataSource, site, "post", "stale 4", "2020-01-01T00:00:00Z");
        PostId stale5 = TestData.autoDraft(dataSource, site, "post", "stale 5", "2020-01-01T00:00:00Z");
        assertThat(housekeeping.purgeAutoDrafts(site)).isEqualTo(5);
        for (PostId gone : List.of(stale1, stale2, stale3, stale4, stale5)) {
            assertThat(TestData.exists(dataSource, gone))
                    .as("purged " + gone.value())
                    .isFalse();
        }
        assertThat(TestData.status(dataSource, fresh)).isEqualTo("auto-draft");
        assertThat(TestData.status(dataSource, draft)).isEqualTo("draft");
        assertThat(housekeeping.purgeAutoDrafts(site)).isZero();
    }
}
