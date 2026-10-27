package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.devui.deployment.DevUiClient.find;
import static org.quarkpress.devui.deployment.DevUiClient.texts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The registries and content counts of a site, read from a dev-mode application that {@link DevUiSeed} fills on every
 * start (each test method gets a new application and an empty database).
 */
class ContentDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = DevUiApp.dev(DevUiSeed.class);

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    void countsGivePostsPerTypeAndStatusTermsPerTaxonomyAndRevisions() throws Exception {
        JsonNode counts = devUi.call("counts", Map.of("siteId", 1));
        assertThat(counts.has("posts")).isTrue();
        assertThat(counts.has("terms")).isTrue();
        assertThat(counts.has("revisions")).isTrue();
        JsonNode posts = counts.get("posts");
        // the draft has no author: counting it also proves the reads run as the system
        assertThat(posts.size()).isEqualTo(2);
        assertThat(posts.has("post")).isTrue();
        assertThat(posts.has("page")).isTrue();
        assertThat(posts.get("post").has("publish")).isTrue();
        assertThat(posts.get("post").has("draft")).isTrue();
        assertThat(posts.get("post").has("future")).isTrue();
        assertThat(posts.get("page").has("publish")).isTrue();
        assertThat(posts.get("post").size()).isEqualTo(3);
        assertThat(posts.get("post").get("publish").asLong()).isEqualTo(2);
        assertThat(posts.get("post").get("draft").asLong()).isEqualTo(1);
        assertThat(posts.get("post").get("future").asLong()).isEqualTo(1);
        assertThat(posts.get("page").size()).isEqualTo(1);
        assertThat(posts.get("page").get("publish").asLong()).isEqualTo(1);
        assertThat(counts.get("terms").has("category")).isTrue();
        assertThat(counts.get("terms").has("post_tag")).isTrue();
        assertThat(counts.get("terms").get("category").asLong()).isEqualTo(2);
        assertThat(counts.get("terms").get("post_tag").asLong()).isEqualTo(2);
        assertThat(counts.get("revisions").asLong()).isEqualTo(1);
    }

    @Test
    void countsOfAnotherSiteAreItsOwn() throws Exception {
        long second = find(devUi.call("sites"), "domain", "second.example.test")
                .get("id")
                .asLong();
        JsonNode counts = devUi.call("counts", Map.of("siteId", second));
        assertThat(counts.has("posts")).isTrue();
        assertThat(counts.has("terms")).isTrue();
        assertThat(counts.has("revisions")).isTrue();
        assertThat(counts.get("posts").size()).isEqualTo(1);
        assertThat(counts.get("posts").has("post")).isTrue();
        assertThat(counts.get("posts").get("post").has("future")).isTrue();
        assertThat(counts.get("terms").has("category")).isTrue();
        assertThat(counts.get("terms").has("post_tag")).isTrue();
        assertThat(counts.get("posts").get("post").get("future").asLong()).isEqualTo(1);
        assertThat(counts.get("terms").get("category").asLong()).isEqualTo(1);
        assertThat(counts.get("terms").get("post_tag").asLong()).isZero();
        assertThat(counts.get("revisions").asLong()).isZero();
    }

    @Test
    void registriesListTheSitesTypesStatusesTaxonomiesAndRoles() throws Exception {
        JsonNode registries = devUi.call("registries", Map.of("siteId", 1));
        assertThat(registries.has("postTypes")).isTrue();
        assertThat(registries.has("statuses")).isTrue();
        assertThat(registries.has("taxonomies")).isTrue();
        assertThat(registries.has("roles")).isTrue();
        assertThat(texts(registries.get("postTypes"), "key"))
                .containsExactlyInAnyOrder("post", "page", "attachment", "wp_block");
        assertThat(find(registries.get("postTypes"), "key", "post").get("label").asText())
                .isEqualTo("Posts");
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
        assertThat(texts(registries.get("taxonomies"), "key")).containsExactlyInAnyOrder("category", "post_tag");
        assertThat(find(registries.get("taxonomies"), "key", "category")
                        .get("hierarchical")
                        .asBoolean())
                .isTrue();
        assertThat(texts(registries.get("roles"), "key"))
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber");
        JsonNode subscriber = find(registries.get("roles"), "key", "subscriber").get("capabilities");
        assertThat(subscriber.size()).isEqualTo(2);
        assertThat(subscriber.has("read")).isTrue();
        assertThat(subscriber.has("level_0")).isTrue();
        assertThat(subscriber.get("read").asBoolean()).isTrue();
        assertThat(subscriber.get("level_0").asBoolean()).isTrue();
        JsonNode editor = find(registries.get("roles"), "key", "editor").get("capabilities");
        assertThat(editor.has("edit_others_posts")).isTrue();
        assertThat(editor.get("edit_others_posts").asBoolean()).isTrue();
    }

    @Test
    void theRolesAreThoseOfTheSelectedSite() throws Exception {
        long second = find(devUi.call("sites"), "domain", "second.example.test")
                .get("id")
                .asLong();
        JsonNode there = find(devUi.call("registries", Map.of("siteId", second)).get("roles"), "key", "subscriber")
                .get("capabilities");
        assertThat(there.size()).isEqualTo(3);
        assertThat(there.has("upload_files")).isTrue();
        assertThat(there.get("upload_files").asBoolean()).isTrue();
        JsonNode main = find(devUi.call("registries", Map.of("siteId", 1)).get("roles"), "key", "subscriber")
                .get("capabilities");
        assertThat(main.has("upload_files")).isFalse();
        assertThat(main.size()).isEqualTo(2);
    }
}
