package org.quarkpress.testing.wpscenario;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The multisite scenarios with two path segments, against WordPress 7.1.3. Active only with {@code -Pwp-parity}. */
@QuarkusTest
@TestProfile(TwoSegmentScenarioParityDbTest.TwoSegments.class)
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class TwoSegmentScenarioParityDbTest extends ScenarioParityBase {

    static final Path SKIPS = Path.of("src/test/resources/wp-scenario-two-segments-skip.txt");

    /** About half of what a run compares: 2 hand-written scenarios and 25 generated ones, about 13 aspects each. */
    static final int MIN_COMPARISONS = 180;

    /** QuarkPress compares two path segments, as the scenarios' WordPress filter does. */
    public static class TwoSegments implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkpress.multisite.path-segments", "2");
        }
    }

    @Test
    void matchesWordPress() throws Exception {
        List<Scenario> scenarios = MultisiteScenarios.all().stream()
                .filter(scenario -> scenario.variant().pathSegments() == 2)
                .toList();
        compareWithWordPress("multisite-scenarios-two-segments", scenarios, SKIPS, MIN_COMPARISONS);
    }
}
