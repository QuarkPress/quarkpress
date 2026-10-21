package org.quarkpress.testing.wpscenario;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The term scenarios against WordPress 7.1.3. Active only with {@code -Pwp-parity}. */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class TermScenarioParityDbTest extends ScenarioParityBase {

    static final Path SKIPS = Path.of("src/test/resources/wp-scenario-terms-skip.txt");

    /** Half of the first green run's 8393 comparisons (all 50 scenarios), rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 4150;

    @Test
    void matchesWordPress() throws Exception {
        compareWithWordPress("term-scenarios", TermScenarios.all(), SKIPS, MIN_COMPARISONS);
    }
}
