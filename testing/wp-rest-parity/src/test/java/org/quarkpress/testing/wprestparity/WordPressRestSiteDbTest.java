package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressTree;

@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WordPressRestSiteDbTest {
    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;
    static Path tree;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        tree = WordPressTree.ensure(System.getProperty("wp.version"));
        site = WordPressRestSite.start(tree, database, System.getProperty("wp.php", "php"));
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
    }

    @AfterAll
    static void stop() {
        try {
            if (site != null) {
                site.close();
                // The harness's files leave the shared tree with the last site of the JVM.
                assertThat(themeDirectory()).doesNotExist();
                assertThat(muPlugin()).doesNotExist();
            }
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    static Path themeDirectory() {
        return tree.toAbsolutePath().normalize().resolve("wp-content/themes").resolve(WordPressRestSite.THEME);
    }

    static Path muPlugin() {
        return tree.toAbsolutePath()
                .normalize()
                .resolve("wp-content/mu-plugins")
                .resolve(WordPressRestSite.MU_PLUGIN);
    }

    @Test
    void theHarnessThemeAndMuPluginAreInTheTreeWhileTheSiteServes() {
        for (String file : WordPressRestSite.THEME_FILES) {
            assertThat(themeDirectory().resolve(file)).isRegularFile();
        }
        assertThat(muPlugin()).isRegularFile();
    }

    @Test
    void servesTheIndexWithPrettyHrefsOnTheNetworkDomain() throws Exception {
        Exchange index = client.send(RestCall.get("anonymous", "/wp-json/"));
        assertThat(index.status()).isEqualTo(200);
        assertThat(index.headerLines("Link")).contains("<http://localhost/wp-json/>; rel=\"https://api.w.org/\"");
        assertThat(index.json().get("namespaces")).contains(TextNode.valueOf("wp/v2"));
    }

    @Test
    void everyUserAuthenticatesWithItsApplicationPasswordAndHasItsRole() throws Exception {
        Map<String, String> roles = Map.of(
                "alice", "administrator",
                "bob", "editor",
                "carol", "author",
                "dave", "contributor",
                "erin", "subscriber");
        for (String user : WordPressRestSite.USERS) {
            JsonNode me = client.send(RestCall.get(user, "/wp-json/wp/v2/users/me?context=edit"))
                    .json();
            assertThat(me.path("username").isTextual()).as("username of %s", me).isTrue();
            assertThat(me.path("username").textValue()).isEqualTo(user);
            assertThat(me.get("email").asText()).isEqualTo(user + "@quarkpress.test");
            assertThat(me.get("roles")).containsExactly(TextNode.valueOf(roles.get(user)));
        }
    }

    @Test
    void theQueryFormReachesTheSameServer() throws Exception {
        Exchange namespace = client.send(RestCall.get("anonymous", "/?rest_route=/wp/v2"));
        assertThat(namespace.status()).isEqualTo(200);
        assertThat(namespace.json().get("namespace").asText()).isEqualTo("wp/v2");
    }

    @Test
    void theInstallersAdminUserIsGoneSoBothSidesHaveFiveUsers() throws Exception {
        JsonNode users = client.send(RestCall.get("alice", "/wp-json/wp/v2/users?context=edit&per_page=100"))
                .json();
        assertThat(users)
                .allSatisfy(u -> assertThat(u.path("username").isTextual())
                        .as("username of %s", u)
                        .isTrue());
        assertThat(users)
                .extracting(u -> u.path("username").textValue())
                .containsExactlyInAnyOrderElementsOf(WordPressRestSite.USERS);
    }
}
