package org.quarkword.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;

/**
 * Without {@code quarkword.rest-wp-v2.editor.dashboard-url}, the editor's dashboard link and the link to a newer
 * autosave's revision screen are WordPress's {@code admin_url()}: the site's URL followed by the admin path, read from
 * the admin screens' key {@code quarkword.admin.path} (here {@code /a/b/}), on a site at the root and below a path.
 * The admin module is not on this module's class path: a build step declares the key as that module does.
 */
class AdminPathDashboardDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    AdminPathDashboardDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(
                            context -> context.produce(new QuarkWordConfigKeysBuildItem(
                                    "admin", "quarkword.admin", Set.of("quarkword.admin.path"))))
                    .produces(QuarkWordConfigKeysBuildItem.class)
                    .build())
            .overrideRuntimeConfigKey("quarkword.admin.path", "/a/b/");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theDashboardFollowsTheAdminPath() throws IOException {
        AgroalDataSource data = Jdbc.dataSource();
        SiteId site = TestData.site(data);
        String host = Jdbc.strings(data, "select domain from qw_site where id = ?", site.value())
                .get(0);
        UserId bob = TestData.profile(data, "bob-" + UUID.randomUUID(), "Bob Baker", UUID.randomUUID() + "@x.test");
        TestData.member(data, site, bob, "[\"editor\"]");
        PostId post = PostFixtures.builder(site)
                .author(bob.value())
                .title("Autosaved")
                .modifiedAt("2026-10-18T10:00:00Z")
                .insert(data);
        long newer = autosaveRow(data, site, post, bob.value());
        JsonNode settings = settings(host, bob, post);
        assertThat(settings.get("__experimentalDashboardLink").asText()).isEqualTo("http://" + host + "/a/b/");
        assertThat(settings.get("autosave").get("editLink").asText())
                .isEqualTo("http://" + host + "/a/b/revision.php?revision=" + newer);

        // a site below a path: its own admin folder
        Jdbc.update(data, "update qw_site set path = '/sub/' where id = ?", site.value());
        assertThat(settings(host, bob, post, "/sub")
                        .get("__experimentalDashboardLink")
                        .asText())
                .isEqualTo("http://" + host + "/sub/a/b/");
    }

    private static JsonNode settings(String host, UserId user, PostId post) throws IOException {
        return settings(host, user, post, "");
    }

    private static JsonNode settings(String host, UserId user, PostId post, String sitePath) throws IOException {
        Response answer = Http.at(host, "test-" + user.value())
                .get(sitePath + "/wp-json/quarkword/v1/editor/post/" + post.value());
        answer.then().statusCode(200);
        return MAPPER.readTree(answer.asString()).get("settings");
    }

    private static long autosaveRow(AgroalDataSource data, SiteId site, PostId post, long author) {
        String marker = "auto-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qw_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", ?, ?, "
                        + PostFixtures.instant() + ")",
                site.value(),
                post.value(),
                author,
                "",
                "newer words",
                marker,
                "{}",
                true,
                author,
                PostFixtures.instantText("2026-10-18T11:00:00Z"));
        long id = Jdbc.count(data, "select id from qw_revision where excerpt = ?", marker);
        Jdbc.update(data, "update qw_revision set excerpt = '' where id = ?", id);
        return id;
    }
}
