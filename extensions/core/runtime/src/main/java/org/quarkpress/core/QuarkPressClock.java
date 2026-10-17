package org.quarkpress.core;

import java.time.Instant;

/**
 * The time source of QuarkPress decisions (publish dates, schedules, timestamps). An application bean implementing
 * this interface replaces the system clock, which is useful in tests.
 */
public interface QuarkPressClock {

    Instant now();
}
