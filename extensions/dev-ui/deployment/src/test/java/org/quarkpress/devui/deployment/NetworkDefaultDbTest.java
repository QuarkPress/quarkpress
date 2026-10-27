package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.devui.deployment.DevUiClient.find;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.FeatureToggles;

/**
 * The sources of the feature values the Dev UI shows on a network whose default switches {@code revisions} off: the
 * network default until a site switch is written, then the site's own switch. The Dev UI cannot set network defaults,
 * so the application sets this one when it starts. Clearing the site's switch brings the network default back.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkDefaultDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = DevUiApp.dev(RevisionsOffByDefault.class);

    /** Switches {@code revisions} off as a network default when the application starts. */
    @ApplicationScoped
    public static class RevisionsOffByDefault {

        @Inject
        FeatureToggles toggles;

        @ActivateRequestContext
        void onStart(@Observes StartupEvent start) {
            toggles.setNetworkDefault("revisions", false);
        }
    }

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    @Order(1)
    void aNetworkDefaultShowsAsTheNetworkSource() throws Exception {
        JsonNode features = devUi.call("features", Map.of("siteId", 1));
        JsonNode revisions = find(features, "key", "revisions");
        assertThat(revisions.get("enabled").asBoolean()).isFalse();
        assertThat(revisions.get("source").asText()).isEqualTo("network");
        JsonNode scheduling = find(features, "key", "scheduling");
        assertThat(scheduling.get("enabled").asBoolean()).isTrue();
        assertThat(scheduling.get("source").asText()).isEqualTo("module");
    }

    @Test
    @Order(2)
    void aSiteSwitchToTheNetworkDefaultShowsAsTheSiteSource() throws Exception {
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "revisions", "enabled", false));
        assertThat(result.get("feature").get("enabled").asBoolean()).isFalse();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("site");
        JsonNode revisions = find(devUi.call("features", Map.of("siteId", 1)), "key", "revisions");
        assertThat(revisions.get("enabled").asBoolean()).isFalse();
        assertThat(revisions.get("source").asText()).isEqualTo("site");
    }

    @Test
    @Order(3)
    void aSiteSwitchAgainstTheNetworkDefaultWins() throws Exception {
        JsonNode result = devUi.call("setFeature", Map.of("siteId", 1, "key", "revisions", "enabled", true));
        assertThat(result.get("feature").get("enabled").asBoolean()).isTrue();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("site");
        JsonNode created =
                devUi.call("createSite", Map.of("domain", "other.example.test", "path", "/", "title", "Other"));
        JsonNode other = find(
                devUi.call(
                        "features",
                        Map.of("siteId", created.get("site").get("id").asLong())),
                "key",
                "revisions");
        assertThat(other.get("enabled").asBoolean()).isFalse();
        assertThat(other.get("source").asText()).isEqualTo("network");
    }

    @Test
    @Order(4)
    void clearingTheSiteSwitchFallsBackToTheNetworkDefault() throws Exception {
        devUi.call("setFeature", Map.of("siteId", 1, "key", "revisions", "enabled", true));
        JsonNode result = devUi.call("clearFeature", Map.of("siteId", 1, "key", "revisions"));
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("feature").get("enabled").asBoolean()).isFalse();
        assertThat(result.get("feature").get("source").asText()).isEqualTo("network");
        JsonNode revisions = find(devUi.call("features", Map.of("siteId", 1)), "key", "revisions");
        assertThat(revisions.get("enabled").asBoolean()).isFalse();
        assertThat(revisions.get("source").asText()).isEqualTo("network");
    }
}
