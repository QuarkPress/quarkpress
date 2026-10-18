package org.quarkpress.testing.wpscenario;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The multisite scenarios with one path segment, against WordPress 7.1.3. Active only with {@code -Pwp-parity}. */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class MultisiteScenarioParityDbTest extends ScenarioParityBase {

    static final Path SKIPS = Path.of("src/test/resources/wp-scenario-skip.txt");

    /** About half of what a run compares: hand-written scenarios and 3 x 20 generated ones, about 12 aspects each. */
    static final int MIN_COMPARISONS = 430;

    @Test
    void matchesWordPress() throws Exception {
        List<Scenario> scenarios = MultisiteScenarios.all().stream()
                .filter(scenario -> scenario.variant().pathSegments() == 1)
                .toList();
        compareWithWordPress("multisite-scenarios", scenarios, SKIPS, MIN_COMPARISONS);
    }
}
