package org.quarkword.devui.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.quarkword.devui.deployment.DevUiClient.find;
import static org.quarkword.devui.deployment.DevUiClient.texts;

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
    void theModulesCardListsEveryInstalledQuarkWordModule() throws Exception {
        JsonNode modules = devUi.data("modules");
        assertThat(texts(modules, "artifactId"))
                .containsExactly(
                        "quarkword-core",
                        "quarkword-dev-ui",
                        "quarkword-multisite",
                        "quarkword-persistence",
                        "quarkword-posts",
                        "quarkword-revisions",
                        "quarkword-scheduling",
                        "quarkword-security",
                        "quarkword-taxonomy");
        String version = modules.get(0).get("version").asText();
        assertThat(version).isNotBlank();
        assertThat(texts(modules, "version")).containsOnly(version);
        JsonNode scheduling = find(modules, "artifactId", "quarkword-scheduling");
        assertThat(scheduling.get("capability").asText()).isEqualTo("org.quarkword.scheduling");
        assertThat(texts(scheduling.get("features"), "key")).containsExactly("scheduling");
        assertThat(scheduling.get("features").get(0).get("defaultEnabled").asBoolean())
                .isTrue();
        assertThat(scheduling.get("migrationBand").isNull()).isTrue();
        assertThat(texts(scheduling.get("configPrefixes"))).containsExactly("quarkword.scheduling");
        JsonNode security = find(modules, "artifactId", "quarkword-security");
        assertThat(security.get("migrationBand").asInt()).isEqualTo(3);
        assertThat(texts(security.get("configPrefixes"))).containsExactly("quarkword.dev", "quarkword.security");
        JsonNode persistence = find(modules, "artifactId", "quarkword-persistence");
        assertThat(persistence.get("migrationBand").asInt()).isEqualTo(1);
        assertThat(texts(persistence.get("configPrefixes"))).containsExactly("quarkword");
        assertThat(find(modules, "artifactId", "quarkword-dev-ui")
                        .get("capability")
                        .asText())
                .isEqualTo("org.quarkword.devui");
        assertThat(find(modules, "artifactId", "quarkword-revisions")
                        .get("migrationBand")
                        .asInt())
                .isEqualTo(6);
    }

    @Test
    void theCardPublishesItsPagesAndMethodsAndCountsTheModules() throws Exception {
        assertThat(texts(devUi.data("pages")))
                .containsExactly(
                        "qwc-quarkword-modules.js",
                        "qwc-quarkword-sites.js",
                        "qwc-quarkword-features.js",
                        "qwc-quarkword-persistence.js",
                        "qwc-quarkword-registries.js",
                        "qwc-quarkword-counts.js",
                        "qwc-quarkword-scheduler.js",
                        "qwc-quarkword-users.js");
        assertThat(texts(devUi.data("methods")))
                .containsExactly(
                        "clearFeature",
                        "counts",
                        "createSite",
                        "devUsers",
                        "features",
                        "persistence",
                        "registries",
                        "scheduler",
                        "setFeature",
                        "siteExists",
                        "sites");
        JsonNode page = DevUiClient.page(devUi.extensions(), "qwc-quarkword-modules.js");
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

    @Test
    void everyPageIsServedByTheDevUi() throws Exception {
        for (String component : texts(devUi.data("pages"))) {
            String element = component.substring(0, component.length() - ".js".length());
            given().get("/q/dev-ui/" + DevUiClient.NAMESPACE + "/" + component)
                    .then()
                    .statusCode(200)
                    .body(containsString("customElements.define('" + element + "'"));
        }
    }
}
