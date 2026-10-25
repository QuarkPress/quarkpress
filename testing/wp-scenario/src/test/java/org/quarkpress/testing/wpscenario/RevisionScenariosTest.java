package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
            assertThat(ScenarioJson.parse(ScenarioJson.toJson(scenario), scenario.generated()))
                    .isEqualTo(scenario);
        }
    }

    @Test
    void generatedScenariosKeepToTheirRules() {
        for (Scenario scenario : RevisionScenarios.generated("seed-b", 20)) {
            Map<Integer, Integer> authors = new HashMap<>();
            Map<Integer, Set<Integer>> autosavers = new HashMap<>();
            long autosaves = 0;
            int inserted = 0;
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.InsertPost insert) {
                    authors.put(++inserted, insert.as());
                }
                if (op instanceof ScenarioOp.Autosave autosave) {
                    autosaves++;
                    assertThat(autosave.as())
                            .as(scenario.id() + " autosaves a post its user cannot edit")
                            .matches(user -> user == 1 || user == authors.get(autosave.post()));
                    autosavers
                            .computeIfAbsent(autosave.post(), post -> new HashSet<>())
                            .add(autosave.as());
                }
                if (op instanceof ScenarioOp.AutosaveOf of) {
                    Set<Integer> users = autosavers.getOrDefault(of.post(), Set.of());
                    if (of.user() == 0) {
                        assertThat(users)
                                .as(scenario.id() + " asks any user's autosave while several users autosaved")
                                .hasSizeLessThanOrEqualTo(1);
                    } else {
                        assertThat(users).contains(of.user());
                    }
                }
                if (op instanceof ScenarioOp.ListRevisions list
                        && list.args().number().isPresent()) {
                    assertThat(list.args().number().getAsInt()).isBetween(1, 3);
                }
                if (op instanceof ScenarioOp.ListRevisions list) {
                    int post = list.post();
                    assertThat(list.as())
                            .as(scenario.id() + " lists a post's revisions as a user who cannot edit it")
                            .matches(user -> user == 1 || user == authors.get(post));
                }
            }
            assertThat(autosaves).as(scenario.id()).isBetween(2L, 8L);
        }
    }

    @Test
    void generatedScenariosAreStableAndShaped() {
        assertThat(RevisionScenarios.generated("seed-a", 5)).isEqualTo(RevisionScenarios.generated("seed-a", 5));
        assertThat(RevisionScenarios.generated("seed-a", 5)).isNotEqualTo(RevisionScenarios.generated("seed-c", 5));
        for (Scenario scenario : RevisionScenarios.generated("seed-b", 20)) {
            assertThat(scenario.ops())
                    .filteredOn(ScenarioOp.ListRevisions.class::isInstance)
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(scenario.ops())
                    .filteredOn(ScenarioOp.UpdatePost.class::isInstance)
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void allHoldsTheHandWrittenThenTwoSeeds() {
        assertThat(RevisionScenarios.all()).hasSize(9 + 2 * RevisionScenarios.GENERATED);
    }

    @Test
    void generatedScenariosAvoidWhatTheTwoSidesAnswerDifferently() {
        for (Scenario scenario : RevisionScenarios.generated("seed-d", 40)) {
            Set<String> saved = new HashSet<>();
            Map<Integer, Integer> last = new HashMap<>();
            Set<Integer> drafts = new HashSet<>();
            Set<Integer> trashed = new HashSet<>();
            int inserted = 0;
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.InsertPost insert) {
                    inserted++;
                    if (insert.fields().status().filter("draft"::equals).isPresent()) {
                        drafts.add(inserted);
                    }
                }
                if (op instanceof ScenarioOp.UpdatePost update) {
                    drafts.remove(update.post());
                }
                if (op instanceof ScenarioOp.TrashPost trash) {
                    trashed.add(trash.post());
                    drafts.remove(trash.post());
                }
                if (op instanceof ScenarioOp.UntrashPost untrash) {
                    trashed.remove(untrash.post());
                }
                if (op instanceof ScenarioOp.Autosave autosave) {
                    int before = last.getOrDefault(autosave.post(), 0);
                    boolean again = !saved.add(autosave.post() + ":" + autosave.as());
                    if (again) {
                        assertThat(autosave.as())
                                .as(scenario.id() + " saves an autosave again that is not the latest row")
                                .isEqualTo(before);
                    }
                    last.put(autosave.post(), autosave.as());
                }
                if (op instanceof ScenarioOp.ReadPost read) {
                    assertThat(drafts)
                            .as(scenario.id() + " reads a draft never updated")
                            .doesNotContain(read.post());
                }
                if (op instanceof ScenarioOp.DeleteRevision) {
                    assertThat(trashed)
                            .as(scenario.id() + " deletes a revision while a post is trashed")
                            .isEmpty();
                }
            }
        }
    }

    @Test
    void revisionsLimitsReachEveryDrawnValueAndNoOther() {
        Set<Integer> keeps = new HashSet<>();
        boolean none = false;
        for (Scenario scenario : RevisionScenarios.all()) {
            if (!scenario.generated()) {
                continue;
            }
            none |= scenario.revisionsKeep().isEmpty();
            scenario.revisionsKeep().ifPresent(keeps::add);
        }
        assertThat(none).isTrue();
        assertThat(keeps).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    void generatedScenariosAlsoTouchPostsInTheTrash() {
        boolean touched = false;
        for (Scenario scenario : RevisionScenarios.all()) {
            Set<Integer> trashed = new HashSet<>();
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.TrashPost trash) {
                    trashed.add(trash.post());
                } else if (op instanceof ScenarioOp.UntrashPost untrash) {
                    trashed.remove(untrash.post());
                } else if (op instanceof ScenarioOp.Autosave autosave) {
                    touched |= trashed.contains(autosave.post());
                } else if (op instanceof ScenarioOp.UpdatePost update) {
                    touched |= trashed.contains(update.post());
                } else if (op instanceof ScenarioOp.UpdatePostMeta meta) {
                    touched |= trashed.contains(meta.post());
                }
            }
        }
        assertThat(touched)
                .as("some generated scenario autosaves, updates or writes meta of a trashed post")
                .isTrue();
    }
}
