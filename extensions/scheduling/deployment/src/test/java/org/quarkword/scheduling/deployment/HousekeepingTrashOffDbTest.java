package org.quarkword.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.scheduling.runtime.Housekeeping;

/** With the trash turned off ({@code empty-trash-days} 0) the threshold is now: everything trashed before it goes. */
class HousekeepingTrashOffDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(HousekeepingTrashOffDbTest.class)
            .overrideConfigKey("quarkword.posts.empty-trash-days", "0");

    @Inject
    @DataSource("quarkword")
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
    void daysZeroPurgesEverythingTrashedBeforeNow() throws Exception {
        PostId earlier = TestData.trashed(dataSource, site, "post", "earlier", 1768478399L);
        PostId thisSecond = TestData.trashed(dataSource, site, "post", "this second", 1768478400L);
        assertThat(housekeeping.purgeTrash(site)).isEqualTo(1);
        assertThat(TestData.exists(dataSource, earlier)).isFalse();
        assertThat(TestData.status(dataSource, thisSecond)).isEqualTo("trash");
    }
}
