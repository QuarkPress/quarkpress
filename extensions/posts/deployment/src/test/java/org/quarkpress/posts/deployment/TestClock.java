package org.quarkpress.posts.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.QuarkPressClock;

/** A clock the test moves; replaces the system clock in the applications that include it. */
@ApplicationScoped
public class TestClock implements QuarkPressClock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-18T10:00:00Z"));

    @Override
    public Instant now() {
        return now.get();
    }

    public void set(Instant instant) {
        now.set(instant);
    }
}
