package org.quarkpress.it.devui;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.quarkpress.devui.deployment.DevUiClient.texts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import java.util.Map;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.devui.deployment.DevUiClient;
import org.quarkpress.devui.deployment.TestDb;

/**
 * The Dev UI of an application with only the core, persistence and dev-ui modules: the card lists those three
 * modules, plans only the persistence pages and methods, and the methods of the absent modules do not exist.
 */
class MinimalDevUiDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addAsResource(
                    new StringAsset("quarkus.datasource.\"quarkpress\".db-kind=" + TestDb.kind() + "\n"
                            + "quarkus.datasource.devservices.enabled=false\n"
                            + "quarkus.http.port=0\n"
                            + "quarkus.http.ssl-port=0\n"),
                    "application.properties"));

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    void onlyTheCorePersistenceAndDevUiModulesAreListed() throws Exception {
        assertThat(texts(devUi.data("modules"), "artifactId"))
                .containsExactly("quarkpress-core", "quarkpress-dev-ui", "quarkpress-persistence");
        given().get("/q/dev-ui/" + DevUiClient.NAMESPACE + "-data.js")
                .then()
                .statusCode(200)
                .body(containsString("export const modules ="))
                .body(not(containsString("export const tokenPage")));
    }

    @Test
    void onlyThePersistencePagesAndMethodsArePlanned() throws Exception {
        assertThat(texts(devUi.data("pages")))
                .containsExactly(
                        "qwc-quarkpress-modules.js", "qwc-quarkpress-features.js", "qwc-quarkpress-persistence.js");
        assertThat(texts(devUi.data("methods"))).containsExactly("features", "persistence", "setFeature");
    }

    @Test
    void thePersistenceMethodsWorkWithoutTheOtherModules() throws Exception {
        assertThat(devUi.call("features", Map.of("siteId", 1))).isEmpty();
        assertThat(texts(devUi.call("persistence").get("migrations"), "version"))
                .containsExactly("1.001", "1.002", "1.003", "1.004", "1.005");
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "x", "enabled", true));
        assertThat(result.get("error").asText()).isEqualTo("unknown QuarkPress feature 'x'");
    }

    @Test
    void theMethodsOfAbsentModulesDoNotExist() throws Exception {
        assertNotFound("sites", Map.of());
        assertNotFound("createSite", Map.of("domain", "two.example.test", "path", "/", "title", "Two"));
        assertNotFound("registries", Map.of("siteId", 1));
        assertNotFound("counts", Map.of("siteId", 1));
        assertNotFound("scheduler", Map.of());
        assertNotFound("devUsers", Map.of());
    }

    private void assertNotFound(String method, Map<String, Object> params) throws Exception {
        DevUiClient.RpcResponse response = devUi.rpc(method, params);
        assertThat(response.result()).isNull();
        assertThat(response.error())
                .isEqualTo(new DevUiClient.RpcError(
                        -32601, "Method [" + DevUiClient.NAMESPACE + "_" + method + "] not found"));
    }
}
