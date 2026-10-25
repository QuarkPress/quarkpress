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

/** The Quarkus scheduler runs the poll on its interval: nothing here calls the poll. */
class ScheduledWiringDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.scheduled("1s", ScheduledWiringDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    @Inject
    SchedulingStatus status;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
    }

    @Test
    void theSchedulerRunsThePollEverySecond() throws Exception {
        PostId due = TestData.scheduled(dataSource, site, "due", "2026-01-15T11:00:00Z");
        Instant deadline = Instant.now().plusSeconds(30);
        while (!TestData.status(dataSource, due).equals("publish")
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(TestData.status(dataSource, due))
                .as("published by the scheduled poll within 30 s")
                .isEqualTo("publish");
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        assertThat(snapshot.lastPollEnded()).isPresent();
        assertThat(snapshot.publishedSinceStart()).isGreaterThanOrEqualTo(1);
    }
}
