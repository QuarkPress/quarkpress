package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SchedulingScenarioFormatTest {

    static final String EXAMPLE = """
            {"id": "scheduling-example", "rule": "SCH12", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "insert_post", "as": 1, "fields": {"title": "Ahead", "status": "publish", "date": "2090-01-15 12:30:00"}},
              {"op": "force_date", "post": 1, "gmt": "2020-01-15 12:30:00"},
              {"op": "run_publish_check", "post": 1},
              {"op": "trash_post", "as": 1, "post": 1},
              {"op": "update_post_meta", "as": 1, "post": 1, "key": "_wp_trash_meta_time", "value": "1000000000"},
              {"op": "run_trash_purge"},
              {"op": "run_auto_draft_purge"}
            ]}
            """;

    static final String USER =
            "{\"op\": \"create_user\", \"username\": \"ed\", \"given_name\": \"\", \"family_name\": \"\"},";
    static final String POST = "{\"op\": \"insert_post\", \"as\": 1, \"fields\": {\"title\": \"P\"}},";

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"SCH1\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everySchedulingOperationIsReadAndWrittenBack() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.ops())
                .containsSubsequence(
                        new ScenarioOp.ForceDate(1, "2020-01-15 12:30:00"),
                        new ScenarioOp.RunPublishCheck(1),
                        new ScenarioOp.UpdatePostMeta(1, 1, "_wp_trash_meta_time", "1000000000"),
                        new ScenarioOp.RunTrashPurge(),
                        new ScenarioOp.RunAutoDraftPurge());
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
        assertThat(SchedulingScenarioJson.posts(new ScenarioOp.ForceDate(2, "2020-01-15 12:30:00")))
                .containsExactly(2);
        assertThat(SchedulingScenarioJson.posts(new ScenarioOp.RunTrashPurge())).isEmpty();
    }

    @Test
    void forcedDatesStayAwayFromTheRun() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"force_date\", \"post\": 1, \"gmt\": \"2030-01-01 00:00:00\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before 2025 or from 2090 on");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        USER + POST + "{\"op\": \"force_date\", \"post\": 1, \"gmt\": \"2020-02-30 00:00:00\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a real Y-m-d H:i:s date");
    }

    @Test
    void theTrashTimeMetaIsDigitsAndOtherProtectedKeysAreRefused() {
        assertThatThrownBy(() -> new ScenarioOp.UpdatePostMeta(1, 1, "_wp_trash_meta_time", "12x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 10 digits");
        assertThatThrownBy(() -> new ScenarioOp.UpdatePostMeta(1, 1, "_wp_trash_meta_time", "12345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's Unix time in seconds is 1 to 10 digits: 12345678901");
        assertThatThrownBy(() -> new ScenarioOp.UpdatePostMeta(1, 1, "_pingme", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("footnotes, _wp_trash_meta_time");
        assertThat(new ScenarioOp.UpdatePostMeta(1, 1, "footnotes", "[]").key()).isEqualTo("footnotes");
    }

    @Test
    void unknownKeysOfASchedulingOperationAreRefused() {
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"run_trash_purge\", \"post\": 1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: unknown key 'post'; known: op");
    }
}
