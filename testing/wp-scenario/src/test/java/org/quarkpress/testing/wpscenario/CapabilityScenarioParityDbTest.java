package org.quarkpress.testing.wpscenario;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The capability scenarios against WordPress 7.1.3. Active only with {@code -Pwp-parity}. */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class CapabilityScenarioParityDbTest extends ScenarioParityBase {

    static final Path SKIPS = Path.of("src/test/resources/wp-scenario-capabilities-skip.txt");

    /** About half of what a run compares; half of the run's 1695 comparisons, rounded down to 50. */
    static final int MIN_COMPARISONS = 800;

    @Test
    void matchesWordPress() throws Exception {
        compareWithWordPress("capability-scenarios", CapabilityScenarios.all(), SKIPS, MIN_COMPARISONS);
    }
}
