package org.quarkpress.it.devui.posts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.devui.deployment.DevUiClient.find;
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
 * The Dev UI of an application with the posts module but without the taxonomy, revisions and scheduling modules: the
 * content provider loads and answers, with no taxonomies, terms or revisions, and the scheduler method does not exist.
 */
class PostsWithoutTaxonomyDevUiDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClass(PostsSeed.class)
                    .addAsResource(
                            new StringAsset("quarkus.datasource.\"quarkpress\".db-kind=" + TestDb.kind() + "\n"
                                    + "quarkus.datasource.devservices.enabled=false\n"
                                    + "quarkus.keycloak.devservices.enabled=false\n"
                                    + "quarkus.oidc.tenant-enabled=false\n"
                                    + "quarkus.http.port=0\n"
                                    + "quarkus.http.ssl-port=0\n"),
                            "application.properties"));

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    void theContentPagesAndMethodsArePlannedButNotTheScheduler() throws Exception {
        assertThat(texts(devUi.data("modules"), "artifactId"))
                .containsExactly(
                        "quarkpress-core",
                        "quarkpress-dev-ui",
                        "quarkpress-multisite",
                        "quarkpress-persistence",
                        "quarkpress-posts",
                        "quarkpress-security");
        assertThat(texts(devUi.data("pages")))
                .containsExactly(
                        "qwc-quarkpress-modules.js",
                        "qwc-quarkpress-sites.js",
                        "qwc-quarkpress-features.js",
                        "qwc-quarkpress-persistence.js",
                        "qwc-quarkpress-registries.js",
                        "qwc-quarkpress-counts.js",
                        "qwc-quarkpress-users.js");
        assertThat(texts(devUi.data("methods")))
                .containsExactly(
                        "counts",
                        "createSite",
                        "devUsers",
                        "features",
                        "persistence",
                        "registries",
                        "setFeature",
                        "sites");
    }

    @Test
    void registriesAnswerPostTypesStatusesAndRolesWithoutTaxonomies() throws Exception {
        JsonNode registries = devUi.call("registries", Map.of("siteId", 1));
        assertThat(texts(registries.get("postTypes"), "key"))
                .containsExactlyInAnyOrder("post", "page", "attachment", "wp_block");
        // every registered status, internal ones included, in registration order
        assertThat(texts(registries.get("statuses"), "name"))
                .containsExactly(
                        "publish",
                        "future",
                        "draft",
                        "pending",
                        "private",
                        "trash",
                        "auto-draft",
                        "inherit",
                        "request-pending",
                        "request-confirmed",
                        "request-failed",
                        "request-completed");
        assertThat(texts(registries.get("roles"), "key"))
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber");
        assertThat(find(registries.get("roles"), "key", "subscriber")
                        .get("capabilities")
                        .has("read"))
                .isTrue();
        assertThat(registries.has("taxonomies")).isFalse();
    }

    @Test
    void countsAnswerPostsWithoutTermsOrRevisions() throws Exception {
        JsonNode counts = devUi.call("counts", Map.of("siteId", 1));
        JsonNode posts = counts.get("posts");
        assertThat(posts.size()).isEqualTo(2);
        assertThat(posts.get("post").size()).isEqualTo(1);
        assertThat(posts.get("post").get("publish").asLong()).isEqualTo(2);
        assertThat(posts.get("page").size()).isEqualTo(1);
        assertThat(posts.get("page").get("draft").asLong()).isEqualTo(1);
        assertThat(counts.has("terms")).isFalse();
        assertThat(counts.has("revisions")).isFalse();
    }

    @Test
    void theSchedulerMethodDoesNotExist() throws Exception {
        DevUiClient.RpcResponse response = devUi.rpc("scheduler", Map.of());
        assertThat(response.result()).isNull();
        assertThat(response.error())
                .isEqualTo(
                        new DevUiClient.RpcError(-32601, "Method [" + DevUiClient.NAMESPACE + "_scheduler] not found"));
        assertThat(devUi.rpc("registries", Map.of("siteId", 1)).error()).isNull();
    }
}
