package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchedulingScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndUseSchedulingOperations() {
        List<Scenario> scenarios = SchedulingScenarios.handWritten();
        assertThat(scenarios).hasSize(4);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("SCH");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
            assertThat(scenario.ops()).anyMatch(ScenarioOp.SchedulingOp.class::isInstance);
        }
    }

    @Test
    void scenarioIdsAreDistinctAndWriteBack() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : SchedulingScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
            assertThat(ScenarioJson.parse(ScenarioJson.toJson(scenario), scenario.generated()))
                    .isEqualTo(scenario);
        }
    }
}
