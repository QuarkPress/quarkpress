package org.quarkpress.devui.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.quarkpress.devui.deployment.DevUiClient.find;
import static org.quarkpress.devui.deployment.DevUiClient.texts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CardDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = DevUiApp.dev();

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    void theModulesCardListsEveryInstalledQuarkPressModule() throws Exception {
        JsonNode modules = devUi.data("modules");
        assertThat(texts(modules, "artifactId"))
                .containsExactly(
                        "quarkpress-core",
                        "quarkpress-dev-ui",
                        "quarkpress-multisite",
                        "quarkpress-persistence",
                        "quarkpress-posts",
                        "quarkpress-revisions",
                        "quarkpress-scheduling",
                        "quarkpress-security",
                        "quarkpress-taxonomy");
        String version = modules.get(0).get("version").asText();
        assertThat(version).isNotBlank();
        assertThat(texts(modules, "version")).containsOnly(version);
        JsonNode scheduling = find(modules, "artifactId", "quarkpress-scheduling");
        assertThat(scheduling.get("capability").asText()).isEqualTo("org.quarkpress.scheduling");
        assertThat(texts(scheduling.get("features"), "key")).containsExactly("scheduling");
        assertThat(scheduling.get("features").get(0).get("defaultEnabled").asBoolean())
                .isTrue();
        assertThat(scheduling.get("migrationBand").isNull()).isTrue();
        assertThat(texts(scheduling.get("configPrefixes"))).containsExactly("quarkpress.scheduling");
        JsonNode security = find(modules, "artifactId", "quarkpress-security");
        assertThat(security.get("migrationBand").asInt()).isEqualTo(3);
        assertThat(texts(security.get("configPrefixes"))).containsExactly("quarkpress.dev", "quarkpress.security");
        JsonNode persistence = find(modules, "artifactId", "quarkpress-persistence");
        assertThat(persistence.get("migrationBand").asInt()).isEqualTo(1);
        assertThat(texts(persistence.get("configPrefixes"))).containsExactly("quarkpress");
        assertThat(find(modules, "artifactId", "quarkpress-dev-ui")
                        .get("capability")
                        .asText())
                .isEqualTo("org.quarkpress.devui");
        assertThat(find(modules, "artifactId", "quarkpress-revisions")
                        .get("migrationBand")
                        .asInt())
                .isEqualTo(6);
    }

    @Test
    void theCardPublishesItsPagesAndMethodsAndCountsTheModules() throws Exception {
        assertThat(texts(devUi.data("pages"))).containsExactly("qwc-quarkpress-modules.js");
        assertThat(devUi.data("methods")).isEmpty();
        JsonNode page = DevUiClient.page(devUi.extensions(), "qwc-quarkpress-modules.js");
        // Dev UI stores the label with a leading space
        assertThat(page.get("staticLabel").asText().strip()).isEqualTo("9");
    }

    @Test
    void theCardDataIsServedUnderTheExpectedNamespace() {
        given().get("/q/dev-ui/" + DevUiClient.NAMESPACE + "-data.js")
                .then()
                .statusCode(200)
                .body(containsString("export const modules ="));
    }
}
