package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The PHP driver's user, role and capability operations, run through a real WordPress. */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class CapabilityDriverDbTest {

    static final String SCENARIO = """
            {"id": "driver-smoke", "rule": "CAP1", "variant": "subdirectory", "ops": [
              {"op": "create_site", "domain": "localhost", "path": "/shop/", "title": "Shop"},
              {"op": "create_user", "username": "ann.lee", "given_name": "Ann", "family_name": "Lee"},
              {"op": "add_role", "site": 1, "role": "shop_manager", "name": "Shop manager", "capabilities": ["read", "edit_posts"]},
              {"op": "add_user", "user": 1, "site": 1, "role": "shop_manager"},
              {"op": "can", "user": 1, "site": 1, "capability": "read", "target": 0},
              {"op": "can", "user": 1, "site": 1, "capability": "edit_posts", "target": 0},
              {"op": "can", "user": 0, "site": 0, "capability": "exist", "target": 0},
              {"op": "can", "user": 0, "site": 0, "capability": "read", "target": 0},
              {"op": "add_user", "user": 1, "site": 0, "role": "no_such_role"},
              {"op": "add_role", "site": 1, "role": "shop_manager", "name": "Again", "capabilities": []},
              {"op": "role_cap", "site": 1, "role": "no_such_role", "capability": "read", "grant": true},
              {"op": "set_role", "user": 1, "site": 1, "role": "no_such_role"},
              {"op": "add_cap", "user": 1, "site": 1, "capability": "upload_files", "grant": false},
              {"op": "network_flag", "name": "menu_plugins", "value": true},
              {"op": "site_setting", "site": 1, "name": "link_manager_enabled", "value": true},
              {"op": "remove_user", "user": 1, "site": 1},
              {"op": "create_user", "username": "ann.lee", "given_name": "", "family_name": ""}
            ]}
            """;

    @Test
    void operationsReportWhatWordPressDid() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            oracle.install(NetworkVariant.SUBDIRECTORY);
            Scenario scenario = ScenarioJson.parse(SCENARIO);
            JsonNode out = oracle.run(scenario);
            Map<String, String> facts =
                    WordPressOutcomes.normalize(scenario, out).facts();
            // the capabilities a role was added with are granted
            assertThat(facts).containsEntry("op4:add_user", "ok");
            assertThat(facts).containsEntry("op5:can", "yes");
            assertThat(facts).containsEntry("op6:can", "yes");
            // a logged-out visitor exists and can do nothing else
            assertThat(facts).containsEntry("op7:can", "yes");
            assertThat(facts).containsEntry("op8:can", "no");
            // WordPress adds a user with a role that does not exist; what it refuses is reported with its error code
            assertThat(facts).containsEntry("op9:add_user", "ok");
            assertThat(facts).containsEntry("op17:create_user", "refused:existing_user_login");
            assertThat(facts).containsEntry("op10:add_role", "changed=false");
            assertThat(facts).containsEntry("op11:role_cap", "failed");
            assertThat(facts).containsEntry("op12:set_role", "failed");
            assertThat(facts).containsEntry("op13:add_cap", "ok");
            assertThat(facts).containsEntry("op14:network_flag", "ok");
            assertThat(facts).containsEntry("op15:site_setting", "ok");
            assertThat(facts).containsEntry("op16:remove_user", "ok");
            // the example of the format test runs to the end
            Scenario example = ScenarioJson.parse(CapabilityScenarioFormatTest.EXAMPLE);
            assertThat(WordPressOutcomes.normalize(example, oracle.run(example)).facts())
                    .containsEntry("op15:can", "yes");
        }
    }
}
