package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WordPressInstallDbTest {

    static final String SMOKE = """
            {"id": "smoke", "rule": "MS3", "variant": "subdirectory", "ops": [
              {"op": "create_site", "domain": "localhost", "path": "/news/", "title": "News"},
              {"op": "lookup", "host": "LOCALHOST", "path": "/news/2026/x"},
              {"op": "lookup", "host": "zz.example", "path": "/"},
              {"op": "create_site", "domain": "localhost", "path": "/blog/", "title": "Reserved"},
              {"op": "set_flag", "site": 1, "flag": "archived", "value": true},
              {"op": "read_options", "site": 1, "names": ["blogname", "posts_per_page", "start_of_week"]}
            ]}
            """;

    @Test
    void installsSingleSiteThenANetworkAndRunsAScenario() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            for (NetworkVariant variant : List.of(NetworkVariant.SUBDIRECTORY, NetworkVariant.SUBDOMAIN)) {
                JsonNode report = oracle.install(variant);
                assertThat(report.get("single_site").get("installed").asBoolean())
                        .isTrue();
                assertThat(report.get("single_site").get("blogname").asText()).isEqualTo("QuarkPress");
                assertThat(report.get("network").get("subdomain_install").asBoolean())
                        .isEqualTo(variant.subdomainInstall());
                assertThat(report.get("network").get("sites").asInt()).isEqualTo(1);
            }
            JsonNode out = oracle.run(ScenarioJson.parse(SMOKE));
            JsonNode results = out.get("results");
            long news = results.get(0).get("id").asLong();
            assertThat(news).isGreaterThan(out.get("main").asLong());
            assertThat(results.get(1).get("result").asText()).isEqualTo("site");
            assertThat(results.get(1).get("id").asLong()).isEqualTo(news);
            assertThat(results.get(2).get("result").asText()).isEqualTo("redirect");
            assertThat(results.get(3).get("error").asText()).isEqualTo("reserved");
            assertThat(results.get(4).get("ok").asBoolean()).isTrue();
            JsonNode values = results.get(5).get("values");
            assertThat(values.get("blogname").asText()).isEqualTo("News");
            assertThat(values.get("posts_per_page").asText()).isEqualTo("10");
            assertThat(values.get("start_of_week").asText()).isEqualTo("1");
            assertThat(out.get("sites").get(0).get("archived").asInt()).isEqualTo(1);
            try (Connection connection = database.connect("scn_1");
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select count(*) from wp_blogs")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(2);
            }
        }
    }
}
