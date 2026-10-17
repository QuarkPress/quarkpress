package org.quarkpress.core.deployment.filters;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.QuarkPressClock;

class SystemClockTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            new QuarkusExtensionTest().withApplicationRoot(jar -> jar.addClasses(SystemClockTest.class));

    @Inject
    QuarkPressClock clock;

    @Test
    void theDefaultClockIsTheSystemTime() {
        assertThat(clock.now())
                .isBetween(
                        Instant.now().minus(Duration.ofSeconds(1)),
                        Instant.now().plus(Duration.ofSeconds(1)));
    }
}
