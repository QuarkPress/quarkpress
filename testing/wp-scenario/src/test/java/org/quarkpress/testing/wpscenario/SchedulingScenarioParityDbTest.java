package org.quarkpress.testing.wpscenario;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The scheduling scenarios against WordPress 7.1.3. Active only with {@code -Pwp-parity}. */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class SchedulingScenarioParityDbTest extends ScenarioParityBase {

    static final Path SKIPS = Path.of("src/test/resources/wp-scenario-scheduling-skip.txt");

    /**
     * Half of the first green run's 3742 comparisons (the 4 hand-written scenarios and the 40 generated ones),
     * rounded down to a multiple of 50.
     */
    static final int MIN_COMPARISONS = 1850;

    @Test
    void matchesWordPress() throws Exception {
        compareWithWordPress("scheduling-scenarios", SchedulingScenarios.all(), SKIPS, MIN_COMPARISONS);
    }
}
