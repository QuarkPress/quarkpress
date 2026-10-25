package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The PHP driver's scheduling operations and the forced-date fixture, run through a real WordPress. */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class SchedulingDriverDbTest {

    static final String SCENARIO = """
            {"id": "driver-scheduling", "rule": "SCH12", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "insert_post", "as": 1, "fields": {"title": "Ahead", "status": "publish", "date": "2090-01-15 12:30:00"}},
              {"op": "insert_post", "as": 1, "fields": {"title": "Binned", "status": "publish", "date": "2020-01-01 10:00:00"}},
              {"op": "insert_post", "as": 1, "fields": {"title": "Stale", "status": "auto-draft", "date": "2020-01-01 10:00:00"}},
              {"op": "insert_post", "as": 1, "fields": {"title": "Fresh", "status": "auto-draft"}},
              {"op": "run_publish_check", "post": 1},
              {"op": "read_post", "post": 1},
              {"op": "force_date", "post": 1, "gmt": "2020-01-15 12:30:00"},
              {"op": "read_post", "post": 1},
              {"op": "run_publish_check", "post": 1},
              {"op": "read_post", "post": 1},
              {"op": "trash_post", "as": 1, "post": 2},
              {"op": "update_post_meta", "as": 1, "post": 2, "key": "_wp_trash_meta_time", "value": "1000000000"},
              {"op": "run_trash_purge"},
              {"op": "run_auto_draft_purge"},
              {"op": "insert_post", "as": 1, "fields": {"title": ""}},
              {"op": "run_publish_check", "post": 5}
            ]}
            """;

    @Test
    void theDriverRunsTheSchedulingOperations() throws Exception {
        Outcome outcome = run(SCENARIO);
        assertThat(outcome.facts())
                .containsEntry("op7:run_publish_check", "ok")
                .containsEntry("op8:read_post:status", "future")
                .containsEntry("op8:read_post:date_gmt", "2090-01-15 12:30:00")
                .containsEntry("op9:force_date", "ok")
                .containsEntry("op10:read_post:status", "future")
                .containsEntry("op10:read_post:date_gmt", "2020-01-15 12:30:00")
                .containsEntry("op10:read_post:date", "2020-01-15 12:30:00")
                .containsEntry("op12:read_post:status", "publish")
                .containsEntry("op12:read_post:modified", "2090-01-15 12:30:00")
                .containsEntry("op12:read_post:modified_gmt", "2090-01-15 12:30:00")
                .containsEntry("op15:run_trash_purge", "survivors=p1,p3,p4")
                .containsEntry("op16:run_auto_draft_purge", "survivors=p1,p4")
                // the format bounds post references by the inserts before them, so the missing post is one WordPress
                // refused to insert (no title, no content)
                .containsEntry("op17:insert_post", "refused:empty_content")
                .containsEntry("op18:run_publish_check", "missing")
                .containsEntry("post#2:deleted", "yes");
    }

    private static Outcome run(String json) throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            oracle.install(NetworkVariant.SUBDIRECTORY);
            Scenario scenario = ScenarioJson.parse(json);
            return WordPressOutcomes.normalize(scenario, oracle.run(scenario));
        }
    }
}
