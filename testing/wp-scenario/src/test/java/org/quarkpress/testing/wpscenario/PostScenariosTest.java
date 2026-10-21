package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

    @Test
    void generatedScenariosAreStableAndValid() {
        assertThat(PostScenarios.generated("seed-a", 5)).isEqualTo(PostScenarios.generated("seed-a", 5));
        for (Scenario scenario : PostScenarios.generated("seed-b", 20)) {
            long inserts = scenario.ops().stream()
                    .filter(ScenarioOp.InsertPost.class::isInstance)
                    .count();
            long queries = scenario.ops().stream()
                    .filter(ScenarioOp.QueryPosts.class::isInstance)
                    .count();
            assertThat(inserts).as(scenario.id()).isBetween(6L, 12L);
            assertThat(queries).as(scenario.id()).isGreaterThanOrEqualTo(3);
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.InsertPost insert) {
                    assertThat(insert.as()).as(scenario.id()).isIn(0, 1);
                }
                if (op instanceof ScenarioOp.UpdatePost update) {
                    assertThat(update.fields().password()).as(scenario.id()).isEmpty();
                }
                if (op instanceof ScenarioOp.QueryPosts query
                        && query.as() == 2
                        && !query.args().statuses().isEmpty()) {
                    assertThat(query.args().types()).as(scenario.id()).containsExactly("post");
                }
            }
            assertThat(ScenarioJson.parse(ScenarioJson.toJson(scenario), true)).isEqualTo(scenario);
        }
    }

    @Test
    void allHoldsTheHandWrittenAndTwoSeedsOfGeneratedScenarios() {
        assertThat(PostScenarios.all()).hasSize(PostScenarios.handWritten().size() + 2 * PostScenarios.GENERATED);
    }

    @Test
    void someGeneratedScenarioUntrashesAPostItTrashedEarlier() {
        List<Scenario> generated =
                new ArrayList<>(PostScenarios.generated("20261020-posts-1", PostScenarios.GENERATED));
        generated.addAll(PostScenarios.generated("20261020-posts-2", PostScenarios.GENERATED));
        assertThat(generated).anyMatch(scenario -> {
            Set<Integer> trashed = new HashSet<>();
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.TrashPost trash) {
                    trashed.add(trash.post());
                }
                if (op instanceof ScenarioOp.UntrashPost untrash && trashed.contains(untrash.post())) {
                    return true;
                }
            }
            return false;
        });
    }
}
