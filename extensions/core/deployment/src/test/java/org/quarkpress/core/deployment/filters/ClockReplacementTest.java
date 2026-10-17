package org.quarkpress.core.deployment.filters;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.QuarkPressClock;

class ClockReplacementTest {

    static final Instant FIXED = Instant.parse("2030-01-02T03:04:05Z");

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(FixedClock.class, ClockReplacementTest.class));

    @ApplicationScoped
    public static class FixedClock implements QuarkPressClock {
        @Override
        public Instant now() {
            return FIXED;
        }
    }

    @Inject
    QuarkPressClock clock;

    @Test
    void anApplicationClockReplacesTheSystemClock() {
        assertThat(clock.now()).isEqualTo(FIXED);
    }
}
