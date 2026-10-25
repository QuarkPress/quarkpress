package org.quarkpress.scheduling.runtime;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Skips this module's scheduled jobs on a node where {@code quarkpress.scheduling.enabled} is false. */
@Singleton
public class SchedulingDisabled implements Scheduled.SkipPredicate {

    @Inject
    SchedulingConfig config;

    @Override
    public boolean test(ScheduledExecution execution) {
        return !config.enabled();
    }
}
