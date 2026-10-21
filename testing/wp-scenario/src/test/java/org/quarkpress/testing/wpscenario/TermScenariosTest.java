package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TermScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndUseTerms() {
        List<Scenario> scenarios = TermScenarios.handWritten();
        assertThat(scenarios).hasSize(10);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("TAX");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
            assertThat(scenario.ops()).anyMatch(ScenarioOp.TermOp.class::isInstance);
        }
    }

    @Test
    void scenarioIdsAreDistinctAndWriteBack() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : TermScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
            assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
        }
    }
}
