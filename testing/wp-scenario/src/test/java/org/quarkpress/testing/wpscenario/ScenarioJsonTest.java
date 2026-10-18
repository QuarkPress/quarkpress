package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioJsonTest {

    static final String EXAMPLE = """
            {"id": "ms3-example", "rule": "MS3", "variant": "subdirectory", "ops": [
              {"op": "create_site", "domain": "localhost", "path": "/news/", "title": "News"},
              {"op": "lookup", "host": "LOCALHOST", "path": "/0/news/x"},
              {"op": "set_flag", "site": 1, "flag": "archived", "value": true},
              {"op": "read_options", "site": 1, "names": ["blogname", "posts_per_page"]}
            ]}
            """;

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"MS3\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everyOperationIsRead() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.id()).isEqualTo("ms3-example");
        assertThat(scenario.rule()).isEqualTo("MS3");
        assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
        assertThat(scenario.generated()).isFalse();
        assertThat(scenario.caseId()).isEqualTo("ms3-example@subdirectory");
        assertThat(scenario.ops())
                .containsExactly(
                        new ScenarioOp.CreateSite("localhost", "/news/", "News"),
                        new ScenarioOp.Lookup("LOCALHOST", "/0/news/x"),
                        new ScenarioOp.SetFlag(1, ScenarioOp.Flag.ARCHIVED, true),
                        new ScenarioOp.ReadOptions(1, List.of("blogname", "posts_per_page")));
    }

    @Test
    void aWrittenScenarioReadsBackEqual() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
        assertThat(ScenarioJson.toJson(scenario).get("ops").get(2).get("flag").asText())
                .isEqualTo("archived");
    }

    @Test
    void theVariantsAreKnown() {
        assertThat(NetworkVariant.named("subdirectory-net").path()).isEqualTo("/net/");
        assertThat(NetworkVariant.SUBDIRECTORY_TWO_SEGMENTS.templateDatabase()).isEqualTo("tpl_subdirectory_2");
        assertThat(ScenarioJson.variantJson(NetworkVariant.SUBDOMAIN)
                        .get("subdomain_install")
                        .asBoolean())
                .isTrue();
        assertThatThrownBy(() -> NetworkVariant.named("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown network variant 'nope'; known: subdirectory, subdirectory-net, subdomain,"
                        + " subdirectory-2");
    }

    @Test
    void inputsOutsideTheComparableScopeAreRefused() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"lookup\", \"host\": \"a.example:8080\", \"path\": \"/\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario host holds only letters, digits, - and .: a.example:8080");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"lookup\", \"host\": \"localhost\", \"path\": \"/x/wp-admin/y\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario lookup path does not hold /wp-admin/: /x/wp-admin/y");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"lookup\", \"host\": \"localhost\", \"path\": \"/x?y\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario lookup path starts with / and holds only letters, digits, - _ . and /: /x?y");
        assertThatThrownBy(
                        () -> ScenarioJson.parse(
                                withOps(
                                        "{\"op\": \"create_site\", \"domain\": \"localhost\", \"path\": \"/x/\", \"title\": \"Tom & Jerry\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario title holds no HTML-special characters or backslashes: Tom & Jerry");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"set_flag\", \"site\": 0, \"flag\": \"spam\", \"value\": true}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario never changes the main site's flags: QuarkPress refuses it and WordPress's API"
                        + " does not");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"read_options\", \"site\": 1, \"names\": [\"blogname\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to site #1, but only 0 sites were created before it");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"read_options\", \"site\": 0, \"names\": [\"siteurl\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("a scenario reads only these options: blogname, ");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"delete_site\", \"site\": 1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: unknown op 'delete_site'");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad has no ops");
    }
}
