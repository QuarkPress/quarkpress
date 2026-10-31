package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.devui.deployment.DevUiClient.find;
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
 * The JSON-RPC methods of the extension against a dev-mode application. {@code QuarkusDevModeTest} starts a new
 * application before each test method, with an empty database, so the tests are independent of each other; the order
 * (reads, then writes) is only for readability.
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
                        "1.001", "1.002", "1.003", "1.004", "1.005", "1.006", "2.001", "3.001", "3.002", "4.001",
                        "5.001", "5.002", "6.001");
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

    @Test
    @Order(5)
    void setFeatureWritesASiteSwitchAndAnswersTheEffectiveValue() throws Exception {
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "scheduling", "enabled", false));
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("feature").get("key").asText()).isEqualTo("scheduling");
        assertThat(result.get("feature").get("module").asText()).isEqualTo("scheduling");
        assertThat(result.get("feature").get("enabled").asBoolean()).isFalse();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("site");
        JsonNode scheduling = find(devUi.call("features", Map.of("siteId", 1)), "key", "scheduling");
        assertThat(scheduling.get("enabled").asBoolean()).isFalse();
        assertThat(scheduling.get("source").asText()).isEqualTo("site");
    }

    @Test
    @Order(6)
    void aFeatureSetToItsDefaultKeepsASiteSwitch() throws Exception {
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "revisions", "enabled", true));
        assertThat(result.get("feature").get("enabled").asBoolean()).isTrue();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("site");
        JsonNode revisions = find(devUi.call("features", Map.of("siteId", 1)), "key", "revisions");
        assertThat(revisions.get("source").asText()).isEqualTo("site");
    }

    @Test
    @Order(7)
    void anUnknownFeatureIsRefusedWithTheServicesMessage() throws Exception {
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "nope", "enabled", true));
        assertThat(result.has("feature")).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("unknown QuarkPress feature 'nope'");
    }

    @Test
    @Order(8)
    void setFeatureOnAnUnknownSiteWritesNothing() throws Exception {
        assertThat(devUi.rpc("setFeature", Map.of("siteId", 99, "key", "revisions", "enabled", false))
                        .error())
                .isEqualTo(
                        new DevUiClient.RpcError(-32603, "Method [quarkpress-dev-ui_setFeature] failed: no site 99"));
        JsonNode features = devUi.call("features", Map.of("siteId", 1));
        assertThat(texts(features, "key")).containsExactly("revisions", "scheduling");
        assertThat(texts(features, "source")).containsExactly("module", "module");
        assertThat(texts(features, "enabled")).containsExactly("true", "true");
    }

    @Test
    @Order(9)
    void clearFeatureRemovesTheSiteSwitchAndAnswersTheModuleValue() throws Exception {
        devUi.call("setFeature", Map.of("siteId", 1, "key", "scheduling", "enabled", false));
        JsonNode result = devUi.call("clearFeature", Map.of("siteId", 1, "key", "scheduling"));
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("feature").get("key").asText()).isEqualTo("scheduling");
        assertThat(result.get("feature").get("module").asText()).isEqualTo("scheduling");
        assertThat(result.get("feature").get("enabled").asBoolean()).isTrue();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("module");
        JsonNode scheduling = find(devUi.call("features", Map.of("siteId", 1)), "key", "scheduling");
        assertThat(scheduling.get("enabled").asBoolean()).isTrue();
        assertThat(scheduling.get("source").asText()).isEqualTo("module");
    }

    @Test
    @Order(10)
    void clearFeatureOnAnUnknownSiteWritesNothing() throws Exception {
        devUi.call("setFeature", Map.of("siteId", 1, "key", "revisions", "enabled", false));
        assertThat(devUi.rpc("clearFeature", Map.of("siteId", 99, "key", "revisions"))
                        .error())
                .isEqualTo(
                        new DevUiClient.RpcError(-32603, "Method [quarkpress-dev-ui_clearFeature] failed: no site 99"));
        JsonNode features = devUi.call("features", Map.of("siteId", 1));
        assertThat(texts(features, "key")).containsExactly("revisions", "scheduling");
        assertThat(texts(features, "source")).containsExactly("site", "module");
        assertThat(texts(features, "enabled")).containsExactly("false", "true");
    }

    @Test
    @Order(11)
    void clearFeatureOfAnUnknownFeatureIsRefusedWithTheServicesMessage() throws Exception {
        JsonNode result = devUi.call("clearFeature", Map.of("siteId", 1, "key", "nope"));
        assertThat(result.has("feature")).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("unknown QuarkPress feature 'nope'");
    }

    @Test
    @Order(12)
    void clearFeatureWithoutASiteSwitchAnswersTheCurrentValue() throws Exception {
        JsonNode result = devUi.call("clearFeature", Map.of("siteId", 1, "key", "revisions"));
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("feature").get("key").asText()).isEqualTo("revisions");
        assertThat(result.get("feature").get("enabled").asBoolean()).isTrue();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("module");
        JsonNode features = devUi.call("features", Map.of("siteId", 1));
        assertThat(texts(features, "source")).containsExactly("module", "module");
        assertThat(texts(features, "enabled")).containsExactly("true", "true");
    }

    @Test
    @Order(13)
    void siteExistsAnswersWhetherASiteHasTheId() throws Exception {
        assertThat(devUi.call("siteExists", Map.of("siteId", 1)).asBoolean()).isTrue();
        assertThat(devUi.call("siteExists", Map.of("siteId", 99)).asBoolean()).isFalse();
        assertThat(devUi.call("siteExists", Map.of("siteId", 0)).asBoolean()).isFalse();
        assertThat(devUi.call("siteExists", Map.of("siteId", -3)).asBoolean()).isFalse();
        long created = devUi.call("createSite", Map.of("domain", "exists.example.test", "path", "/", "title", "Exists"))
                .get("site")
                .get("id")
                .asLong();
        assertThat(devUi.call("siteExists", Map.of("siteId", created)).asBoolean())
                .isTrue();
        assertThat(devUi.call("siteExists", Map.of("siteId", created + 1)).asBoolean())
                .isFalse();
    }

    /**
     * Creating a site needs the network capability {@code create_sites}, which only the system identity holds here (no
     * user is signed in): the call succeeds only because it runs as the system.
     */
    @Test
    @Order(14)
    void createSiteCreatesASiteThatSitesLists() throws Exception {
        JsonNode result = devUi.call("createSite", Map.of("domain", "two.example.test", "path", "/", "title", "Two"));
        assertThat(result.has("error")).isFalse();
        JsonNode site = result.get("site");
        assertThat(site.get("id").asLong()).isGreaterThan(1);
        assertThat(site.get("domain").asText()).isEqualTo("two.example.test");
        assertThat(site.get("path").asText()).isEqualTo("/");
        assertThat(site.get("name").asText()).isEqualTo("Two");
        assertThat(site.get("status").asText()).isEqualTo("active");
        assertThat(texts(devUi.call("sites"), "id")).contains(site.get("id").asText());
        JsonNode features =
                devUi.call("features", Map.of("siteId", site.get("id").asLong()));
        assertThat(texts(features, "source")).containsOnly("module");
    }

    @Test
    @Order(15)
    void anEmptyDomainIsRefusedWithTheServicesMessage() throws Exception {
        JsonNode result = devUi.call("createSite", Map.of("domain", "", "path", "/", "title", "Empty"));
        assertThat(result.has("site")).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("a site domain must not be empty");
        assertThat(texts(devUi.call("sites"), "id")).containsExactly("1");
    }

    @Test
    @Order(16)
    void aSiteAtAnExistingAddressInAnotherCaseIsRefusedWithTheServicesMessage() throws Exception {
        devUi.call("createSite", Map.of("domain", "three.example.test", "path", "/", "title", "Three"));
        int before = devUi.call("sites").size();
        JsonNode result =
                devUi.call("createSite", Map.of("domain", "THREE.example.test", "path", "/", "title", "Again"));
        assertThat(result.has("site")).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("a site already exists at three.example.test/");
        assertThat(devUi.call("sites")).hasSize(before);
    }
}
