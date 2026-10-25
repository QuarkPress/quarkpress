package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class SchedulingOutcomesTest {

    @Test
    void resultsBecomeFacts() {
        Outcome outcome = new Outcome();
        Map<Long, Integer> index = Map.of(40L, 1, 41L, 2, 42L, 3);
        SchedulingOutcomes.result(outcome, 3, new ScenarioOp.RunPublishCheck(1), json("{\"ok\": true}"), index);
        SchedulingOutcomes.result(
                outcome, 4, new ScenarioOp.ForceDate(1, "2020-01-01 00:00:00"), json("{\"missing\": true}"), index);
        SchedulingOutcomes.result(outcome, 5, new ScenarioOp.RunTrashPurge(), json("{\"survivors\": [40, 42]}"), index);
        SchedulingOutcomes.result(outcome, 6, new ScenarioOp.RunAutoDraftPurge(), json("{\"survivors\": []}"), index);
        SchedulingOutcomes.result(outcome, 7, new ScenarioOp.RunPublishCheck(2), json("{\"error\": \"boom\"}"), index);
        SchedulingOutcomes.result(
                outcome, 8, new ScenarioOp.ForceDate(3, "2020-01-01 00:00:00"), json("{\"ok\": false}"), index);
        assertThat(outcome.facts())
                .containsEntry("op3:run_publish_check", "ok")
                .containsEntry("op4:force_date", "missing")
                .containsEntry("op5:run_trash_purge", "survivors=p1,p3")
                .containsEntry("op6:run_auto_draft_purge", "survivors=(none)")
                .containsEntry("op7:run_publish_check", "refused:boom")
                .containsEntry("op8:force_date", "failed");
    }

    @Test
    void aPurgeResultWithoutSurvivorsIsAShapeError() {
        assertThatThrownBy(() -> SchedulingOutcomes.result(
                        new Outcome(), 1, new ScenarioOp.RunTrashPurge(), json("{\"ok\": true}"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("survivors");
    }

    private static JsonNode json(String text) {
        try {
            return OracleJson.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
