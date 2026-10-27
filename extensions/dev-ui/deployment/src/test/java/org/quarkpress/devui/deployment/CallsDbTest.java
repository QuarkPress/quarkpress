package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.devui.deployment.DevUiClient.texts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The JSON-RPC methods of the extension against a dev-mode application. A dev-mode test class shares one application
 * and one database, so the reads run first, in order, before any test that writes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CallsDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = DevUiApp.dev();

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    @Order(1)
    void sitesListsTheMainSite() throws Exception {
        JsonNode sites = devUi.call("sites");
        assertThat(sites).hasSize(1);
        JsonNode main = sites.get(0);
        assertThat(main.get("id").asLong()).isEqualTo(1);
        assertThat(main.get("domain").asText()).isEqualTo("localhost");
        assertThat(main.get("path").asText()).isEqualTo("/");
        assertThat(main.get("name").asText()).isEqualTo("QuarkPress");
        assertThat(main.get("publicFlag").asInt()).isEqualTo(1);
        assertThat(main.get("status").asText()).isEqualTo("active");
        assertThat(main.get("deleted").asBoolean()).isFalse();
    }

    @Test
    @Order(2)
    void featuresShowEveryFeatureWithItsValueAndSource() throws Exception {
        JsonNode features = devUi.call("features", Map.of("siteId", 1));
        assertThat(texts(features, "key")).containsExactly("revisions", "scheduling");
        assertThat(texts(features, "module")).containsExactly("revisions", "scheduling");
        assertThat(texts(features, "source")).containsExactly("module", "module");
        assertThat(texts(features, "enabled")).containsExactly("true", "true");
    }

    @Test
    @Order(3)
    void persistenceShowsTheUnitTheDatasourceAndTheAppliedMigrations() throws Exception {
        JsonNode persistence = devUi.call("persistence");
        assertThat(persistence.get("persistenceUnit").asText()).isEqualTo("quarkpress");
        assertThat(persistence.get("datasource").asText()).isEqualTo("quarkpress");
        assertThat(persistence.get("dbKind").asText()).isEqualTo(TestDb.kind());
        assertThat(persistence.get("historyTable").asText()).isEqualTo("qp_schema_history");
        JsonNode migrations = persistence.get("migrations");
        assertThat(texts(migrations, "version"))
                .containsExactly(
                        "1.001", "1.002", "1.003", "1.004", "1.005", "2.001", "3.001", "4.001", "5.001", "5.002",
                        "6.001");
        assertThat(texts(migrations, "success")).containsOnly("true");
        assertThat(migrations.get(0).get("script").asText()).isEqualTo("V1_001__persistence_create_tables.sql");
    }

    @Test
    @Order(4)
    void anUnknownOrInvalidSiteIsRefused() throws Exception {
        assertThat(devUi.rpc("features", Map.of("siteId", 99)).error())
                .isEqualTo(new DevUiClient.RpcError(-32603, "Method [quarkpress-dev-ui_features] failed: no site 99"));
        assertThat(devUi.rpc("features", Map.of("siteId", 0)).error())
                .isEqualTo(new DevUiClient.RpcError(
                        -32603, "Method [quarkpress-dev-ui_features] failed: site id must be positive: 0"));
        DevUiClient.RpcResponse known = devUi.rpc("features", Map.of("siteId", 1));
        assertThat(known.error()).isNull();
        assertThat(known.result()).hasSize(2);
    }
}
