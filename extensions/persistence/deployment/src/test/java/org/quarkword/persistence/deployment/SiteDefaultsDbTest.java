package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.FeatureValue;
import org.quarkword.core.FeatureValue.Source;
import org.quarkword.core.OptionStore;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.persistence.FeatureToggles;
import org.quarkword.persistence.SiteScope;
import org.quarkword.persistence.SiteScopedNativeSql;
import org.quarkword.persistence.runtime.SiteDefaults;

@SiteScopedNativeSql
class SiteDefaultsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(SiteDefaultsDbTest.class)
            .addBuildChainCustomizer(chain ->
                    TestFeatures.declare(chain, new SiteFeatureBuildItem("demo-off", "test", false, "Off by default")));

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    SiteScope scope;

    @Inject
    OptionStore store;

    @Inject
    SiteContext context;

    @Inject
    FeatureToggles toggles;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    /** The values WordPress stores for a new site, written out independently of {@link SiteDefaults}. */
    private static Map<String, JsonNode> expected(String title) {
        Map<String, JsonNode> expected = new LinkedHashMap<>();
        expected.put("blogname", TextNode.valueOf(title));
        expected.put("blogdescription", TextNode.valueOf(""));
        expected.put("timezone_string", TextNode.valueOf(""));
        expected.put("gmt_offset", TextNode.valueOf("0"));
        expected.put("date_format", TextNode.valueOf("F j, Y"));
        expected.put("time_format", TextNode.valueOf("g:i a"));
        expected.put("start_of_week", IntNode.valueOf(1));
        expected.put("posts_per_page", IntNode.valueOf(10));
        expected.put("default_category", IntNode.valueOf(0));
        expected.put("show_on_front", TextNode.valueOf("posts"));
        expected.put("page_on_front", IntNode.valueOf(0));
        expected.put("page_for_posts", IntNode.valueOf(0));
        expected.put("permalink_structure", TextNode.valueOf("/%year%/%monthnum%/%day%/%postname%/"));
        expected.put("category_base", TextNode.valueOf(""));
        expected.put("tag_base", TextNode.valueOf(""));
        expected.put("wp_attachment_pages_enabled", TextNode.valueOf("0"));
        expected.put("wp_page_for_privacy_policy", IntNode.valueOf(0));
        expected.put("default_comment_status", TextNode.valueOf("open"));
        expected.put("default_ping_status", TextNode.valueOf("open"));
        expected.put("use_smilies", TextNode.valueOf("1"));
        expected.put("default_post_format", TextNode.valueOf("0"));
        expected.put("site_icon", TextNode.valueOf("0"));
        expected.put("WPLANG", TextNode.valueOf(""));
        expected.put("qw_features", JsonNodeFactory.instance.objectNode());
        return expected;
    }

    /** {@link #expected(String)} plus {@code home} and {@code siteurl}, both the site's address. */
    private static Map<String, JsonNode> expected(String title, String address) {
        Map<String, JsonNode> expected = expected(title);
        expected.put("home", TextNode.valueOf(address));
        expected.put("siteurl", TextNode.valueOf(address));
        return expected;
    }

    /**
     * The options the migrations give a site that exists when they run, an installation's main site: a new site's,
     * without {@code WPLANG}, which WordPress's installer does not write (a network's later sites get it empty).
     */
    private static Map<String, JsonNode> withoutLanguage(Map<String, JsonNode> options) {
        Map<String, JsonNode> seeded = new LinkedHashMap<>(options);
        seeded.remove("WPLANG");
        return seeded;
    }

    @Test
    void theDefaultsAreWordPressValues() {
        Map<String, JsonNode> defaults = SiteDefaults.forNewSite("Blog");
        assertThat(List.copyOf(defaults.keySet()))
                .containsExactlyElementsOf(expected("Blog").keySet());
        assertThat(defaults).isEqualTo(expected("Blog"));
    }

    @Test
    void newSitesGetTheFrontOptions() {
        Map<String, JsonNode> defaults = SiteDefaults.forNewSite("Blog");
        assertThat(defaults.get("permalink_structure"))
                .isEqualTo(TextNode.valueOf("/%year%/%monthnum%/%day%/%postname%/"));
        assertThat(defaults.get("category_base")).isEqualTo(TextNode.valueOf(""));
        assertThat(defaults.get("tag_base")).isEqualTo(TextNode.valueOf(""));
        assertThat(defaults.get("wp_attachment_pages_enabled")).isEqualTo(TextNode.valueOf("0"));
        assertThat(defaults.get("wp_page_for_privacy_policy")).isEqualTo(IntNode.valueOf(0));
        assertThat(defaults).doesNotContainKeys("home", "siteurl");
        Map<String, JsonNode> addressed = SiteDefaults.forNewSite("Blog", "http://wp.test/site2");
        assertThat(addressed.get("home")).isEqualTo(TextNode.valueOf("http://wp.test/site2"));
        assertThat(addressed.get("siteurl")).isEqualTo(TextNode.valueOf("http://wp.test/site2"));
        assertThat(addressed).containsAllEntriesOf(defaults);
        assertThat(SiteDefaults.names()).isEqualTo(addressed.keySet());
        assertThat(SiteDefaults.address("https", "wp.test", "/")).isEqualTo("https://wp.test");
        assertThat(SiteDefaults.address("http", "wp.test", "/site2/")).isEqualTo("http://wp.test/site2");
    }

    @Test
    void eachCallReturnsFreshValues() {
        ((ObjectNode) SiteDefaults.forNewSite("A").get("qw_features")).put("x", true);
        assertThat(SiteDefaults.forNewSite("A").get("qw_features")).isEqualTo(JsonNodeFactory.instance.objectNode());
    }

    /**
     * Other tests of this module may change the main site's options, so only presence and JSON type are checked here.
     * {@link #theMigrationSeedsExactlyTheDefaults} checks the values the same migration writes, on a site of its own.
     */
    @Test
    void theMainSiteHasTheDefaults() throws Exception {
        SiteId main = SiteId.of(1);
        Map<String, JsonNode> stored = scope.call(main, () -> store.autoloaded(main));
        withoutLanguage(SiteDefaults.forNewSite("QuarkWord")).forEach((name, value) -> {
            assertThat(stored).containsKey(name);
            assertThat(stored.get(name).getNodeType()).as("type of %s", name).isEqualTo(value.getNodeType());
        });
        assertThat(stored).doesNotContainKey("WPLANG");
    }

    @Test
    void theMigrationSeedsExactlyTheDefaults() throws Exception {
        String title = "Drift Site";
        String domain = uniqueDomain();
        long site = insertSite(domain, "/", title);
        String address = "http://" + domain;
        try {
            runSeedMigration();
            Map<String, Stored> stored = storedOptions(site);
            assertThat(List.copyOf(stored.keySet()))
                    .containsExactlyInAnyOrderElementsOf(
                            withoutLanguage(expected(title, address)).keySet());
            assertThat(values(stored)).isEqualTo(withoutLanguage(expected(title, address)));
            assertThat(values(stored)).isEqualTo(withoutLanguage(SiteDefaults.forNewSite(title, address)));
            stored.forEach((name, option) ->
                    assertThat(option.autoload()).as("autoload of %s", name).isTrue());
        } finally {
            deleteSite(site);
        }
    }

    @Test
    void theMigrationKeepsOptionsASiteAlreadyHas() throws Exception {
        long site = insertSite("Seeded Name");
        try {
            insertOption(site, "blogname", "\"Mine\"", false);
            insertOption(site, "posts_per_page", "25", false);
            insertOption(site, "qw_features", "{\"demo-off\":true}", true);
            runSeedMigration();
            Map<String, Stored> stored = storedOptions(site);
            assertThat(stored.get("blogname")).isEqualTo(new Stored(TextNode.valueOf("Mine"), false));
            assertThat(stored.get("posts_per_page")).isEqualTo(new Stored(IntNode.valueOf(25), false));
            ObjectNode features = JsonNodeFactory.instance.objectNode().put("demo-off", true);
            assertThat(stored.get("qw_features")).isEqualTo(new Stored(features, true));
            Map<String, JsonNode> rest = withoutLanguage(expected("Seeded Name"));
            rest.keySet().removeAll(List.of("blogname", "posts_per_page", "qw_features"));
            rest.forEach((name, value) ->
                    assertThat(stored.get(name)).as("seeded %s", name).isEqualTo(new Stored(value, true)));
            assertThat(stored).hasSize(25);
        } finally {
            deleteSite(site);
        }
    }

    @Test
    void existingSitesAreSeeded() throws Exception {
        String domain = uniqueDomain();
        long root = insertSite(domain, "/", "Root");
        long sub = insertSite(domain, "/sub/", "Sub");
        try {
            insertOption(sub, "permalink_structure", "\"/%postname%/\"", true);
            runSeedMigration();
            for (long site : new long[] {root, sub}) {
                Map<String, Stored> stored = storedOptions(site);
                assertThat(stored)
                        .as("site %s", site)
                        .containsKeys(
                                "permalink_structure",
                                "category_base",
                                "tag_base",
                                "wp_attachment_pages_enabled",
                                "wp_page_for_privacy_policy",
                                "home",
                                "siteurl");
                assertThat(stored.get("category_base")).isEqualTo(new Stored(TextNode.valueOf(""), true));
                assertThat(stored.get("tag_base")).isEqualTo(new Stored(TextNode.valueOf(""), true));
                assertThat(stored.get("wp_attachment_pages_enabled"))
                        .isEqualTo(new Stored(TextNode.valueOf("0"), true));
                assertThat(stored.get("wp_page_for_privacy_policy")).isEqualTo(new Stored(IntNode.valueOf(0), true));
            }
            Map<String, Stored> rootOptions = storedOptions(root);
            assertThat(rootOptions.get("home")).isEqualTo(new Stored(TextNode.valueOf("http://" + domain), true));
            assertThat(rootOptions.get("siteurl")).isEqualTo(new Stored(TextNode.valueOf("http://" + domain), true));
            assertThat(rootOptions.get("permalink_structure").value())
                    .isEqualTo(TextNode.valueOf("/%year%/%monthnum%/%day%/%postname%/"));
            Map<String, Stored> subOptions = storedOptions(sub);
            assertThat(subOptions.get("home"))
                    .isEqualTo(new Stored(TextNode.valueOf("http://" + domain + "/sub"), true));
            assertThat(subOptions.get("siteurl"))
                    .isEqualTo(new Stored(TextNode.valueOf("http://" + domain + "/sub"), true));
            assertThat(subOptions.get("permalink_structure").value()).isEqualTo(TextNode.valueOf("/%postname%/"));
        } finally {
            deleteSite(sub);
            deleteSite(root);
        }
    }

    /**
     * The main site (the first row) of a sub-directory network gets the {@code /blog/} structure. The main site's
     * seeded structure is deleted and seeded again, so the sites this test adds decide the result; afterwards the
     * migration runs once more with them gone, which restores the default structure the main site started with.
     */
    @Test
    void theMainSiteOfASubDirectoryNetworkGetsTheBlogStructure() throws Exception {
        long main = Jdbc.count(dataSource, "select min(id) from qw_site");
        String mainDomain = Jdbc.strings(dataSource, "select domain from qw_site where id = ?", main)
                .get(0);
        long sub = insertSite(mainDomain, "/second/", "Second");
        try {
            deleteOption(main, "permalink_structure");
            runSeedMigration();
            assertThat(storedOptions(main).get("permalink_structure"))
                    .isEqualTo(new Stored(TextNode.valueOf("/blog/%year%/%monthnum%/%day%/%postname%/"), true));
            assertThat(storedOptions(sub).get("permalink_structure").value())
                    .isEqualTo(TextNode.valueOf("/%year%/%monthnum%/%day%/%postname%/"));
        } finally {
            deleteSite(sub);
            deleteOption(main, "permalink_structure");
            runSeedMigration();
        }
        assertThat(storedOptions(main).get("permalink_structure").value())
                .isEqualTo(TextNode.valueOf("/%year%/%monthnum%/%day%/%postname%/"));
    }

    @Test
    void aTitleWithQuoteBackslashAndControlCharactersIsStoredAsTheSameJsonString() throws Exception {
        String title = "Tom \"T\" \\ back" + (char) 1 + "ctl\ttab\nnl " + (char) 0xE9 + " end";
        long site = insertSite(title);
        try {
            runSeedMigration();
            assertThat(storedOptions(site).get("blogname").value()).isEqualTo(TextNode.valueOf(title));
        } finally {
            deleteSite(site);
        }
    }

    @Test
    void emptyFeatureSwitchesBehaveLikeNoOptionAtAll() throws Exception {
        long seeded = insertSite("Seeded Features");
        long absent = insertSite("Absent Features");
        try {
            runSeedMigration();
            deleteOption(absent, "qw_features");
            assertThat(storedOptions(seeded)).containsKey("qw_features");
            assertThat(storedOptions(absent)).doesNotContainKey("qw_features");
            for (long id : new long[] {seeded, absent}) {
                SiteId site = SiteId.of(id);
                assertThat(valueIn(site)).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
                scope.run(site, () -> toggles.setForSite("demo-off", true));
                assertThat(valueIn(site)).isEqualTo(new FeatureValue("demo-off", true, Source.SITE));
                assertThat(storedOptions(id).get("qw_features").value())
                        .isEqualTo(JsonNodeFactory.instance.objectNode().put("demo-off", true));
                scope.run(site, () -> toggles.clearForSite("demo-off"));
                assertThat(valueIn(site)).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
            }
        } finally {
            deleteSite(seeded);
            deleteSite(absent);
        }
    }

    /** The feature's value for {@code site}, read in a new request so no per-request cache is reused. */
    private FeatureValue valueIn(SiteId site) {
        AtomicReference<FeatureValue> value = new AtomicReference<>();
        Requests.fresh(() -> scope.run(site, () -> value.set(context.features().value("demo-off"))));
        return value.get();
    }

    private record Stored(JsonNode value, boolean autoload) {}

    private static Map<String, JsonNode> values(Map<String, Stored> stored) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        stored.forEach((name, option) -> values.put(name, option.value()));
        return values;
    }

    private static String uniqueDomain() {
        return "defaults-" + UUID.randomUUID() + ".test";
    }

    private long insertSite(String name) {
        return insertSite(uniqueDomain(), "/", name);
    }

    private long insertSite(String domain, String path, String name) {
        Jdbc.update(dataSource, "insert into qw_site (domain, path, name) values (?, ?, ?)", domain, path, name);
        return Jdbc.count(dataSource, "select id from qw_site where domain = ? and path = ?", domain, path);
    }

    private void deleteSite(long site) {
        Jdbc.update(dataSource, "delete from qw_site where id = ?", site);
    }

    private void deleteOption(long site, String name) {
        Jdbc.update(dataSource, "delete from qw_option where site_id = ? and name = ?", site, name);
    }

    private void insertOption(long site, String name, String json, boolean autoload) {
        String value = TestDb.kind().equals("mysql") ? "?" : "cast(? as json)";
        Jdbc.update(
                dataSource,
                "insert into qw_option (site_id, name, value, autoload) values (?, ?, " + value + ", ?)",
                site,
                name,
                json,
                autoload);
    }

    private Map<String, Stored> storedOptions(long site) throws SQLException, IOException {
        Map<String, Stored> stored = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select name, value, autoload from qw_option where site_id = ? order by id")) {
            statement.setLong(1, site);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stored.put(rows.getString(1), new Stored(JSON.readTree(rows.getString(2)), rows.getBoolean(3)));
                }
            }
        }
        return stored;
    }

    /** Runs the seeding migrations' own scripts again, in order, as they ran when Flyway applied them. */
    private void runSeedMigration() throws SQLException, IOException {
        runScript("db/quarkword/" + TestDb.kind() + "/V1_005__persistence_site_defaults.sql");
        runScript("db/quarkword/" + TestDb.kind() + "/V1_006__persistence_more_site_defaults.sql");
        runScript("db/quarkword/" + TestDb.kind() + "/V1_007__persistence_front_defaults.sql");
    }

    private void runScript(String path) throws SQLException, IOException {
        String script;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder code = new StringBuilder();
        for (String line : script.lines().toList()) {
            if (!line.startsWith("--")) {
                code.append(line).append('\n');
            }
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : code.toString().split(";")) {
                if (!sql.isBlank()) {
                    statement.executeUpdate(sql);
                }
            }
        }
    }
}
