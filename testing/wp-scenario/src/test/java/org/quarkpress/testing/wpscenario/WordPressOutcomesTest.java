package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class WordPressOutcomesTest {

    static final String SCENARIO = """
            {"id": "normalize", "rule": "MS4", "variant": "subdirectory", "ops": [
              {"op": "create_site", "domain": "Shop.Example", "path": "Shop", "title": ""},
              {"op": "create_site", "domain": "shop.example", "path": "/shop/", "title": "Again"},
              {"op": "lookup", "host": "shop.example", "path": "/shop/x"},
              {"op": "lookup", "host": "zz.example", "path": "/"},
              {"op": "lookup", "host": "localhost", "path": "/nosuch/"},
              {"op": "lookup", "host": "localhost", "path": "/"},
              {"op": "set_flag", "site": 1, "flag": "archived", "value": true},
              {"op": "set_flag", "site": 2, "flag": "spam", "value": true},
              {"op": "read_options", "site": 1, "names": ["blogname", "posts_per_page"]}
            ]}
            """;

    static final String DRIVER_OUT = """
            {"main": 1, "results": [
              {"op": "create_site", "id": 7},
              {"op": "create_site", "error": "site_taken"},
              {"op": "lookup", "result": "site", "id": 7},
              {"op": "lookup", "result": "redirect", "to": "http://localhost/"},
              {"op": "lookup", "result": "not_installed"},
              {"op": "lookup", "result": "site", "id": 1},
              {"op": "set_flag", "ok": true},
              {"op": "set_flag", "missing": true},
              {"op": "read_options", "values": {"blogname": "Site 7", "posts_per_page": "10"}}
            ], "sites": [
              {"index": 1, "id": 7, "domain": "Shop.Example", "path": "/Shop/", "public": 1, "archived": 1,
               "mature": 0, "spam": 0, "deleted": 0}
            ]}
            """;

    @Test
    void driverObservationsAreNormalized() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO);
        JsonNode out = OracleJson.MAPPER.readTree(DRIVER_OUT);
        assertThat(WordPressOutcomes.normalize(scenario, out).facts())
                .containsExactly(
                        java.util.Map.entry("op1:create_site", "created #1"),
                        java.util.Map.entry("op2:create_site", "refused:taken"),
                        java.util.Map.entry("op3:lookup", "#1"),
                        java.util.Map.entry("op4:lookup", "none"),
                        java.util.Map.entry("op5:lookup", "none"),
                        java.util.Map.entry("op6:lookup", "#0"),
                        java.util.Map.entry("op7:set_flag", "ok"),
                        java.util.Map.entry("op8:set_flag", "missing"),
                        java.util.Map.entry("op9:read_options:blogname", "Site #1"),
                        java.util.Map.entry("op9:read_options:posts_per_page", "10"),
                        java.util.Map.entry(
                                "site#1", "shop.example/shop/ public=1 archived=1 mature=0 spam=0 deleted=0"));
    }

    @Test
    void refusalsFallIntoTheSameCategoriesOnBothSides() {
        assertThat(Refusals.ofWordPress("site_taken")).isEqualTo("taken");
        assertThat(Refusals.ofQuarkPress("a site already exists at localhost/x/"))
                .isEqualTo("taken");
        assertThat(Refusals.ofWordPress("site_empty_domain")).isEqualTo("empty");
        assertThat(Refusals.ofQuarkPress("a site domain must not be empty")).isEqualTo("empty");
        assertThat(Refusals.ofWordPress("reserved")).isEqualTo("reserved");
        assertThat(Refusals.ofQuarkPress("the first path segment 'blog' is reserved for WordPress on subdirectory"
                        + " sites of the network; reserved: page"))
                .isEqualTo("reserved");
        assertThat(Refusals.ofWordPress("db_insert_error")).isEqualTo("invalid: db_insert_error");
        assertThat(Refusals.ofQuarkPress("a site domain has no port: x:1"))
                .isEqualTo("invalid: a site domain has no port: x:1");
    }

    private static Scenario titled(String title) {
        return ScenarioJson.parse("{\"id\": \"t\", \"rule\": \"MS1\", \"variant\": \"subdirectory\", \"ops\": ["
                + "{\"op\": \"create_site\", \"domain\": \"a.example\", \"path\": \"/\", \"title\": \"" + title
                + "\"},{\"op\": \"read_options\", \"site\": 1, \"names\": [\"blogname\", \"page_on_front\"]},"
                + "{\"op\": \"read_options\", \"site\": 0, \"names\": [\"blogname\"]},"
                + "{\"op\": \"set_flag\", \"site\": 1, \"flag\": \"public\", \"value\": false},"
                + "{\"op\": \"lookup\", \"host\": \"a.example\", \"path\": \"/\"}]}");
    }

    private static final String TITLED_OUT = """
            {"main": 1, "results": [
              {"op": "create_site", "id": 7},
              {"op": "read_options", "values": {"blogname": "%s", "page_on_front": null}},
              {"op": "read_options", "values": {"blogname": "Site 1"}},
              {"op": "set_flag", "ok": false},
              {"op": "lookup", "result": "site", "id": 99}
            ], "sites": []}
            """;

    @Test
    void onlyADefaultTitleOfAnUntitledSiteIsNormalized() throws Exception {
        assertThat(WordPressOutcomes.normalize(titled(""), OracleJson.MAPPER.readTree(TITLED_OUT.formatted("Site 7")))
                        .facts())
                .containsEntry("op2:read_options:blogname", "Site #1")
                .containsEntry("op2:read_options:page_on_front", "(missing)")
                .containsEntry("op3:read_options:blogname", "Site 1")
                .containsEntry("op4:set_flag", "failed")
                .containsEntry("op5:lookup", "#?99");
        assertThat(WordPressOutcomes.normalize(
                                titled("Named"), OracleJson.MAPPER.readTree(TITLED_OUT.formatted("Site 7")))
                        .facts())
                .containsEntry("op2:read_options:blogname", "Site 7");
        assertThat(WordPressOutcomes.normalize(
                                titled("Site 7"), OracleJson.MAPPER.readTree(TITLED_OUT.formatted("Site 7")))
                        .facts())
                .containsEntry("op2:read_options:blogname", "Site 7");
    }

    @Test
    void missingSitesAndMalformedDriverOutputAreReportedClearly() throws Exception {
        Scenario scenario = titled("");
        assertThat(WordPressOutcomes.normalize(
                                scenario,
                                OracleJson.MAPPER.readTree(TITLED_OUT
                                        .replace(
                                                "{\"values\": {\"blogname\": \"%s\", \"page_on_front\": null}}",
                                                "{\"missing\": true}")
                                        .replace(
                                                "\"op\": \"read_options\", \"values\": {\"blogname\": \"Site 1\"}",
                                                "\"op\": \"read_options\", \"missing\": true")))
                        .facts())
                .containsEntry("op3:read_options:blogname", "(missing)");
        String[] malformed = {
            "{\"results\": [], \"sites\": []}",
            "{\"main\": 1, \"results\": [{}], \"sites\": []}",
            "{\"main\": 1, \"results\": [], \"sites\": []}",
            TITLED_OUT.formatted("x").replace("\"sites\": []", "\"x\": []"),
            TITLED_OUT.formatted("x").replace("{\"op\": \"create_site\", \"id\": 7}", "{\"op\": \"create_site\"}"),
            TITLED_OUT.formatted("x").replace("\"ok\": false", "\"nok\": 1"),
            TITLED_OUT.formatted("x").replace("\"sites\": []", "\"sites\": [{\"index\": 1}]"),
        };
        for (String json : malformed) {
            assertThatThrownBy(() -> WordPressOutcomes.normalize(scenario, OracleJson.MAPPER.readTree(json)))
                    .as(json)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("t");
        }
        assertThatThrownBy(() -> WordPressOutcomes.normalize(scenario, OracleJson.MAPPER.readTree(malformed[2])))
                .hasMessage("the driver reported 0 results for the 5 ops of t@subdirectory");
    }

    @Test
    void aRedirectAndANotInstalledAnswerBothComparesAsNoSite() throws Exception {
        Scenario scenario =
                ScenarioJson.parse("{\"id\": \"r\", \"rule\": \"MS1\", \"variant\": \"subdirectory\", \"ops\": ["
                        + "{\"op\": \"lookup\", \"host\": \"a.example\", \"path\": \"/\"},"
                        + "{\"op\": \"lookup\", \"host\": \"b.example\", \"path\": \"/\"}]}");
        JsonNode out = OracleJson.MAPPER.readTree(
                "{\"main\": 1, \"results\": [{\"result\": \"redirect\", \"to\": \"http://x/\"},"
                        + "{\"result\": \"not_installed\"}], \"sites\": []}");
        assertThat(WordPressOutcomes.normalize(scenario, out).facts())
                .containsExactly(
                        java.util.Map.entry("op1:lookup", Outcome.NONE),
                        java.util.Map.entry("op2:lookup", Outcome.NONE));
    }

    @Test
    void aSiteRecordThatDisagreesWithTheCreationOrderIsRejected() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO);
        String wrongId = DRIVER_OUT.replace("{\"index\": 1, \"id\": 7,", "{\"index\": 1, \"id\": 8,");
        assertThatThrownBy(() -> WordPressOutcomes.normalize(scenario, OracleJson.MAPPER.readTree(wrongId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("normalize@subdirectory")
                .hasMessageContaining("site #1")
                .hasMessageContaining("id 8")
                .hasMessageContaining("id 7");
        String unknownIndex = DRIVER_OUT.replace("{\"index\": 1, \"id\": 7,", "{\"index\": 2, \"id\": 7,");
        assertThatThrownBy(() -> WordPressOutcomes.normalize(scenario, OracleJson.MAPPER.readTree(unknownIndex)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site #2");
    }
}
