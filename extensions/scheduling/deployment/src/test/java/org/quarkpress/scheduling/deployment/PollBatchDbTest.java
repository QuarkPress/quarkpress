package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.PublishPoll;

/** Sites in id order, each site's due posts in date then id order, at most the batch size per site and poll. */
class PollBatchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SchedulingApp.with(PollBatchDbTest.class).overrideConfigKey("quarkpress.scheduling.batch-size", "2");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    PublishPoll poll;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    @Test
    void sitesAndPostsComeInOrderWithAtMostBatchSizePerSite() throws Exception {
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        PostId a3 = TestData.scheduled(dataSource, first, "a3", "2026-01-15T10:00:00Z");
        PostId a1 = TestData.scheduled(dataSource, first, "a1", "2026-01-15T09:00:00Z");
        PostId a2 = TestData.scheduled(dataSource, first, "a2", "2026-01-15T09:00:00Z");
        PostId b1 = TestData.scheduled(dataSource, second, "b1", "2026-01-15T11:00:00Z");
        assertThat(poll.poll())
                .containsExactly(
                        new SchedulingStatus.Published(first, a1),
                        new SchedulingStatus.Published(first, a2),
                        new SchedulingStatus.Published(second, b1));
        assertThat(TestData.status(dataSource, a3)).isEqualTo("future");
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(first, a3));
        assertThat(poll.poll()).isEmpty();
    }
}
