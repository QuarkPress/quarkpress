package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Test
    void generatedScenariosKeepToTheirRules() {
        List<Scenario> scenarios = SchedulingScenarios.generated("seed-a", 20);
        assertThat(scenarios).hasSize(20);
        assertThat(scenarios.stream().filter(s -> s.revisionsKeep().isPresent()).count())
                .isBetween(4L, 16L);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).isEqualTo("generated");
            assertThat(scenario.generated()).isTrue();
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            scenario.revisionsKeep().ifPresent(keep -> assertThat(keep).isZero());
            Map<Integer, String> status = new HashMap<>();
            Set<Integer> trashed = new HashSet<>();
            Set<Integer> gone = new HashSet<>();
            int inserted = 0;
            ScenarioOp previous = null;
            boolean tail = false;
            for (ScenarioOp op : scenario.ops()) {
                if (!(op instanceof ScenarioOp.ReadPost) && !(op instanceof ScenarioOp.ListRevisions)) {
                    assertThat(tail)
                            .as(scenario.id() + " has an operation after its tail")
                            .isFalse();
                }
                switch (op) {
                    case ScenarioOp.InsertPost insert -> {
                        assertThat(insert.as()).isBetween(1, 2);
                        if (insert.as() == 2) {
                            assertThat(insert.fields().type().orElse("post"))
                                    .as(scenario.id() + " has the author insert a page")
                                    .isEqualTo("post");
                        }
                        status.put(
                                ++inserted,
                                insert.fields().status().orElse("draft")
                                        + (insert.fields()
                                                        .date()
                                                        .map(d -> d.startsWith("2090"))
                                                        .orElse(false)
                                                ? "-future"
                                                : ""));
                    }
                    case ScenarioOp.ForceDate force -> {
                        assertThat(status.get(force.post()))
                                .as(scenario.id() + " forces a date on an unscheduled post")
                                .isEqualTo("publish-future");
                        assertThat(force.gmt()).startsWith("2020-");
                    }
                    case ScenarioOp.UpdatePostMeta meta -> {
                        assertThat(meta.key()).isEqualTo("_wp_trash_meta_time");
                        assertThat(meta.value()).isEqualTo("1000000000");
                        assertThat(previous)
                                .as(scenario.id() + " sets the trash time without a trash just before")
                                .isEqualTo(new ScenarioOp.TrashPost(1, meta.post()));
                    }
                    case ScenarioOp.UpdatePost update -> {
                        assertThat(update.as()).isEqualTo(1);
                        assertThat(trashed)
                                .as(scenario.id() + " updates a trashed post")
                                .doesNotContain(update.post());
                        assertThat(gone)
                                .as(scenario.id() + " updates a deleted post")
                                .doesNotContain(update.post());
                    }
                    case ScenarioOp.TrashPost trash -> {
                        assertThat(trash.as()).isEqualTo(1);
                        assertThat(trashed)
                                .as(scenario.id() + " trashes a trashed post")
                                .doesNotContain(trash.post());
                        assertThat(gone)
                                .as(scenario.id() + " trashes a deleted post")
                                .doesNotContain(trash.post());
                        trashed.add(trash.post());
                    }
                    case ScenarioOp.UntrashPost untrash -> {
                        assertThat(untrash.as()).isEqualTo(1);
                        assertThat(trashed)
                                .as(scenario.id() + " untrashes a post not in the trash")
                                .contains(untrash.post());
                        trashed.remove(untrash.post());
                    }
                    case ScenarioOp.DeletePost delete -> {
                        assertThat(delete.as()).isEqualTo(1);
                        assertThat(delete.force()).isTrue();
                        trashed.remove(delete.post());
                        gone.add(delete.post());
                    }
                    case ScenarioOp.ListRevisions list -> {
                        tail = true;
                        assertThat(list.as()).isEqualTo(1);
                        assertThat(gone)
                                .as(scenario.id() + " lists revisions of a deleted post")
                                .doesNotContain(list.post());
                    }
                    case ScenarioOp.ReadPost read -> tail = true;
                    default -> {
                        // users and the purges: no post reference to check
                    }
                }
                previous = op;
            }
            assertThat(inserted).isBetween(2, 4);
            assertThat(scenario.ops()).anyMatch(ScenarioOp.SchedulingOp.class::isInstance);
            assertThat(scenario.ops())
                    .filteredOn(ScenarioOp.ReadPost.class::isInstance)
                    .hasSize(inserted);
        }
    }

    @Test
    void allHoldsTheHandWrittenThenTwoSeeds() {
        List<Scenario> all = SchedulingScenarios.all();
        assertThat(all).hasSize(4 + 2 * SchedulingScenarios.GENERATED);
        assertThat(all.subList(0, 4)).isEqualTo(SchedulingScenarios.handWritten());
        assertThat(all.subList(4, 4 + SchedulingScenarios.GENERATED))
                .isEqualTo(SchedulingScenarios.generated("20261025-scheduling-1", SchedulingScenarios.GENERATED));
        assertThat(all.get(4).id()).isEqualTo("gen-scheduling-1-0");
        assertThat(all.get(4 + SchedulingScenarios.GENERATED).id()).isEqualTo("gen-scheduling-2-0");
        assertThat(SchedulingScenarios.generated("seed-a", 5)).isEqualTo(SchedulingScenarios.generated("seed-a", 5));
        assertThat(SchedulingScenarios.generated("seed-a", 5)).isNotEqualTo(SchedulingScenarios.generated("seed-c", 5));
    }
}
