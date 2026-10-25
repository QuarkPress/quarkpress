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

/** With the module switched off the scheduler skips the poll on this node; a direct call still works. */
class PollDisabledDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.scheduled("1s", PollDisabledDbTest.class)
            .overrideConfigKey("quarkpress.scheduling.enabled", "false");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    PublishPoll poll;

    @Inject
    SchedulingStatus status;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
    }

    @Test
    void enabledFalseStopsTheScheduledPollsButNotDirectCalls() throws Exception {
        PostId due = TestData.scheduled(dataSource, site, "due", "2026-01-15T11:00:00Z");
        Thread.sleep(3000);
        assertThat(status.snapshot().lastPollStarted())
                .as("no poll ran in 3 seconds")
                .isEmpty();
        assertThat(TestData.status(dataSource, due)).isEqualTo("future");
        assertThat(poll.poll()).containsExactly(new SchedulingStatus.Published(site, due));
        assertThat(TestData.status(dataSource, due)).isEqualTo("publish");
    }
}
