package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndRunOnTheSubdirectoryNetwork() {
        List<Scenario> scenarios = CapabilityScenarios.handWritten();
        assertThat(scenarios).hasSize(12);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("CAP");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
        }
    }

    @Test
    void generatedScenariosAreStableAndCheckALot() {
        assertThat(CapabilityScenarios.generated("seed-a", 5)).isEqualTo(CapabilityScenarios.generated("seed-a", 5));
        for (Scenario scenario : CapabilityScenarios.generated("seed-b", 20)) {
            long checks = scenario.ops().stream()
                    .filter(ScenarioOp.Can.class::isInstance)
                    .count();
            assertThat(checks).as(scenario.id()).isGreaterThanOrEqualTo(15);
            Set<String> logins = new HashSet<>();
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.CreateUser user) {
                    assertThat(logins.add(user.username().toLowerCase(Locale.ROOT)))
                            .as("distinct logins in " + scenario.id())
                            .isTrue();
                }
            }
            assertThat(ScenarioJson.parse(ScenarioJson.toJson(scenario), true)).isEqualTo(scenario);
        }
    }

    @Test
    void scenarioIdsAreDistinct() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : CapabilityScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
        }
    }
}
