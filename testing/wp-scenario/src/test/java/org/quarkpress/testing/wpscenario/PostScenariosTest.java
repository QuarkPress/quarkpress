package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PostScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndRunOnTheSubdirectoryNetwork() {
        List<Scenario> scenarios = PostScenarios.handWritten();
        assertThat(scenarios).hasSize(10);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("POST");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
            assertThat(scenario.ops()).anyMatch(ScenarioOp.InsertPost.class::isInstance);
        }
    }

    @Test
    void oneScenarioComparesGuidsUnderPlainPermalinksAndOneSetsAPrettyStructure() {
        List<Scenario> scenarios = PostScenarios.handWritten();
        assertThat(scenarios).filteredOn(PostRecords::includeGuid).isNotEmpty();
        assertThat(scenarios)
                .anyMatch(scenario -> scenario.ops().stream()
                        .anyMatch(op -> op instanceof ScenarioOp.SiteOption option
                                && option.name().equals("permalink_structure")
                                && option.value().startsWith("/%postname%")));
    }

    @Test
    void scenarioIdsAreDistinct() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : PostScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
        }
    }
}
