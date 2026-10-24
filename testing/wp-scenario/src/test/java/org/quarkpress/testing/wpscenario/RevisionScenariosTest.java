package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RevisionScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndUseRevisions() {
        List<Scenario> scenarios = RevisionScenarios.handWritten();
        assertThat(scenarios).hasSize(9);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("REV");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
            assertThat(scenario.ops()).anyMatch(ScenarioOp.RevisionOp.class::isInstance);
        }
        assertThat(scenarios.stream()
                        .filter(scenario -> scenario.revisionsKeep().isPresent()))
                .hasSize(2);
    }

    @Test
    void scenarioIdsAreDistinctAndWriteBack() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : RevisionScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
            assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
        }
    }
}
