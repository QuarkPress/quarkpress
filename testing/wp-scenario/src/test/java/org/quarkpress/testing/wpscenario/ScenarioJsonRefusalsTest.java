package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class ScenarioJsonRefusalsTest {

    private static String scenario(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"MS3\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    private static final String LOOKUP = "{\"op\": \"lookup\", \"host\": \"localhost\", \"path\": \"%s\"}";

    @Test
    void textThatIsNotAComparableScenarioIsRefusedWithAMessage() {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put(scenario(LOOKUP.formatted("/").replace("}", ", \"port\": 80}")), "unknown key 'port'");
        refused.put(
                scenario("{\"op\": \"set_flag\", \"site\": 1, \"flag\": \"spam\", \"value\": true, \"x\": 1}"),
                "unknown key 'x'");
        refused.put(scenario(LOOKUP.formatted("/")).replace("\"ops\"", "\"extra\": 1, \"ops\""), "unknown key 'extra'");
        refused.put(scenario(LOOKUP.formatted("/")) + " {}", "Trailing token");
        refused.put(scenario(LOOKUP.formatted("/")).replace("\"rule\"", "\"id\": \"x\", \"rule\""), "Duplicate field");
        refused.put("{not json", "scenario JSON is not valid");
        refused.put("[]", "a scenario must be a JSON object");
        refused.put(scenario("5"), "every op must be an object");
        refused.put(scenario("{\"op\": \"read_options\", \"site\": 0, \"names\": \"blogname\"}"), "non-empty list");
        refused.put(scenario("{\"op\": \"read_options\", \"site\": 0, \"names\": []}"), "non-empty list");
        refused.put(scenario("{\"op\": \"read_options\", \"site\": 0, \"names\": [5]}"), "holds only text");
        refused.put(scenario("{\"op\": \"read_options\", \"site\": 0, \"names\": [null]}"), "holds only text");
        refused.put(
                scenario("{\"op\": \"read_options\", \"site\": 0, \"names\": [\"blogname\", \"blogname\"]}"),
                "lists 'blogname' twice");
        refused.put(
                scenario("{\"op\": \"read_options\", \"site\": \"0\", \"names\": [\"blogname\"]}"),
                "'site' must be a whole number");
        refused.put(
                scenario("{\"op\": \"set_flag\", \"site\": 1, \"flag\": \"spam\", \"value\": 1}"),
                "'value' must be true or false");
        refused.put(scenario("{\"op\": 1}"), "'op' must be text");
        refused.put(
                scenario("{\"op\": \"set_flag\", \"site\": 1, \"flag\": \"nope\", \"value\": true}"),
                "unknown site flag 'nope'");
        refused.put(scenario(LOOKUP.formatted("/x\\\\y")), "lookup path starts with /");
        refused.put(scenario(LOOKUP.formatted("/x%20y")), "lookup path starts with /");
        refused.put(scenario(LOOKUP.formatted("/X/WP-ADMIN/y")), "does not hold /wp-admin/");
        refused.put(
                scenario("{\"op\": \"create_site\", \"domain\": \"localhost\", \"path\": \"/x/\", \"title\": \""
                        + "t".repeat(101) + "\"}"),
                "at most 100 characters");
        refused.put(scenario(LOOKUP.formatted("/")).replace("\"bad\"", "\"Bad Id\""), "lower-case letters");
        refused.put(
                "{\"id\": \"bad\", \"rule\": \"MS3\", \"variant\": \"subdirectory\", \"ops\": {}}",
                "'ops' must be a list");
        refused.forEach((json, message) -> assertThatThrownBy(() -> ScenarioJson.parse(json))
                .as(json)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message));
    }

    @Test
    void aSlashlessAdminPathAndTheGeneratedOverloadAreAccepted() throws Exception {
        String json = scenario(LOOKUP.formatted("/wp-admin") + "," + LOOKUP.formatted("/x/wp-admin"));
        assertThat(ScenarioJson.parse(OracleJson.MAPPER.readTree(json), true).generated())
                .isTrue();
        assertThat(ScenarioJson.parse(json).ops())
                .containsExactly(
                        new ScenarioOp.Lookup("localhost", "/wp-admin"),
                        new ScenarioOp.Lookup("localhost", "/x/wp-admin"));
    }

    @Test
    void thePublicFlagIsRead() {
        assertThat(ScenarioJson.parse(
                                scenario(
                                        "{\"op\": \"create_site\", \"domain\": \"localhost\", \"path\": \"/x/\", \"title\": \"\"},"
                                                + "{\"op\": \"set_flag\", \"site\": 1, \"flag\": \"public\", \"value\": false}"))
                        .ops()
                        .get(1))
                .isEqualTo(new ScenarioOp.SetFlag(1, ScenarioOp.Flag.PUBLIC, false));
        assertThat(List.of(ScenarioOp.Flag.values())).hasSize(5);
    }
}
