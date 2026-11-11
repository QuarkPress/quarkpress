package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressScenarioOracle;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress 7.1.3 answers for the editor-extras references' verification lists: the blocks reference's
 * Part G ({@code b01}-{@code b15}: synced patterns, the {@code wp_pattern_category} taxonomy, block types, the block
 * renderer, block patterns and their categories), the editor-environment reference's Part G ({@code v01}-{@code v16}:
 * themes, the editor page, oEmbed, URL-mode uploads), the index and route keys ({@code v19}, {@code v20}) and the meta
 * {@code key} of a refused REST-named meta field ({@code c01}), with the OPTIONS bodies of the nine routes the slice
 * serves. Every item runs on a subdirectory site of its own ({@code /fb01/}, {@code /fv01/}, ...), except the editor
 * page items {@code v07}-{@code v10}, which run on the main site (the only site whose {@code wp-admin} is served)
 * through login cookie sessions and delete what they created. Exchanges are recorded with {@link
 * VerificationFacts#record}, setup answers and values read out of pages with {@link VerificationFacts#note}; each item
 * switches off in its own {@code finally} every {@code qp_parity_*} switch it turns on and removes the users and files
 * it added. The items that need WordPress to reach its own host ({@code v15}, {@code v16} and the draft of {@code v11})
 * run on a second network served by four {@code php -S} workers. Only expectations backed by recorded editor traffic
 * are asserted; everything else is recorded.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class EditorExtrasFactsDbTest {

    static ScenarioDatabase database;
    /** The network of every item, served by one worker. */
    static WordPressRestSite network;

    static RestClient networkClient;
    /** The network whose server has four workers, started by the first item that needs a self-request. */
    static WordPressRestSite loopbackNetwork;

    static RestClient loopbackClient;

    /** Items that run on the main site. */
    static final Set<String> MAIN_SITE_ITEMS = Set.of("v07", "v08", "v09", "v10");
    /** Items that run wholly on the four-worker network. */
    static final Set<String> LOOPBACK_ITEMS = Set.of("v15", "v16");

    static final String THEME_SWITCH = "qp_parity_quarkpress_theme";
    static final String NO_CORE_PATTERNS_SWITCH = "qp_parity_no_core_patterns";
    static final String PATTERNS_SWITCH = "qp_parity_patterns";
    static final String BLOCK_TYPES_SWITCH = "qp_parity_block_types";
    static final String NOTICE_BLOCK_SWITCH = "qp_parity_notice_block";
    static final String REST_NAME_META_SWITCH = "qp_parity_rest_name_meta";

    /** The core patterns WordPress registers first, in this order (recorded editor traffic). */
    static final List<String> CORE_PATTERNS = List.of(
            "core/query-standard-posts",
            "core/query-medium-posts",
            "core/query-small-posts",
            "core/query-grid-posts",
            "core/query-large-title-posts",
            "core/query-offset-posts",
            "core/navigation-overlay",
            "core/navigation-overlay-black-bg",
            "core/navigation-overlay-accent-bg",
            "core/navigation-overlay-centered",
            "core/navigation-overlay-centered-with-extras");

    static final String PARAGRAPH = "<!-- wp:paragraph --><p>Pattern text.</p><!-- /wp:paragraph -->";

    /** The item the running test records under; null when it records nothing. */
    String item;

    /** The network and client the running item uses. */
    WordPressRestSite wp;

    RestClient client;

    /** The item site's URL prefix ({@code /fb01}), or {@code ""} for the main site. */
    String site;

    /** A login session: the two cookies as one {@code Cookie} header value, the REST nonce and the session token. */
    record Session(String user, String cookie, String nonce, String token) {}

    /** The blocks and pattern categories of the blocks reference's examples, by their ids on the item site. */
    record Blocks(long synced, long carols, long unsynced, long hero, long buttons) {}

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        // Harness rule (as in the media facts runs): the installing administrator is the current user while the
        // template network is created, so the network's upload_filetypes is written for that user.
        System.setProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER, "true");
        try {
            network = WordPressRestSite.start(tree(), database, php());
        } finally {
            System.clearProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER);
        }
        networkClient = new RestClient(network.root(), RestClient.applicationPasswords(network));
    }

    static Path tree() throws Exception {
        return WordPressTree.ensure(System.getProperty("wp.version"));
    }

    static String php() {
        return System.getProperty("wp.php", "php");
    }

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            try {
                if (loopbackNetwork != null) {
                    loopbackNetwork.close();
                }
                if (network != null) {
                    network.close();
                }
            } finally {
                if (database != null) {
                    database.close();
                }
            }
        }
    }

    @BeforeEach
    void open(TestInfo info) throws Exception {
        String method = info.getTestMethod().orElseThrow().getName();
        boolean itemName = method.length() > 3
                && (method.charAt(0) == 'b' || method.charAt(0) == 'v' || method.charAt(0) == 'c')
                && Character.isDigit(method.charAt(1))
                && Character.isDigit(method.charAt(2));
        item = itemName ? method.substring(0, 3) : null;
        wp = network;
        client = networkClient;
        site = null;
        if (item == null) {
            return;
        }
        if (LOOPBACK_ITEMS.contains(item)) {
            useLoopback();
            return;
        }
        if (MAIN_SITE_ITEMS.contains(item)) {
            site = "";
            return;
        }
        createSite();
    }

    @AfterEach
    void clearUploads() throws Exception {
        // Harness rule: the shared WordPress tree keeps no files of an item.
        if (site != null && !site.isEmpty()) {
            wp.operation("clear_uploads", args().put("site", sitePath()));
        }
    }

    /** Creates the item's site ({@code /f<item>/}) on the network in use. */
    void createSite() throws Exception {
        site = "/f" + item;
        wp.operation("create_site", args().put("path", sitePath()).put("title", item));
        wp.operation("clear_uploads", args().put("site", sitePath()));
    }

    /** Moves the running item to the four-worker network (started on first use), on a site of the same path. */
    void useLoopback() throws Exception {
        if (site != null && !site.isEmpty()) {
            wp.operation("clear_uploads", args().put("site", sitePath()));
        }
        if (loopbackNetwork == null) {
            loopbackNetwork = WordPressRestSite.start(tree(), database, php(), 4);
            loopbackClient = new RestClient(loopbackNetwork.root(), RestClient.applicationPasswords(loopbackNetwork));
        }
        wp = loopbackNetwork;
        client = loopbackClient;
        createSite();
    }

    // ---- blocks reference, Part G ---------------------------------------------------------------------------------

    @Test
    void b01ReadShapesOfABlock() throws Exception {
        Blocks blocks = blocks();
        send(RestCall.get("alice", api("wp/v2/blocks/" + blocks.synced())));
        Exchange edit = send(RestCall.get("alice", api("wp/v2/blocks/" + blocks.synced() + "?context=edit")));
        assertThat(fieldNames(edit.json()))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "password",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "template",
                        "meta",
                        "wp_pattern_category",
                        "wp_pattern_sync_status",
                        "_links");
        send(RestCall.get("alice", api("wp/v2/blocks/" + blocks.synced() + "?context=embed")));
        send(RestCall.get("dave", api("wp/v2/blocks/" + blocks.synced())));
        send(RestCall.get("dave", api("wp/v2/blocks/" + blocks.synced() + "?context=edit")));
    }

    @Test
    void b02RevisionsOfABlock() throws Exception {
        Blocks blocks = blocks();
        for (long block : List.of(blocks.synced(), blocks.unsynced())) {
            send(RestCall.json(
                    "alice",
                    "POST",
                    api("wp/v2/blocks/" + block),
                    "{\"content\":\"<!-- wp:paragraph --><p>Updated.</p><!-- /wp:paragraph -->\"}"));
            send(RestCall.get("alice", api("wp/v2/blocks/" + block + "/revisions?context=edit")));
            send(RestCall.get("alice", api("wp/v2/blocks/" + block + "/revisions")));
        }
    }

    @Test
    void b03SyncStatus() throws Exception {
        Blocks blocks = blocks();
        Exchange created = send(RestCall.json(
                "alice",
                "POST",
                api("wp/v2/blocks"),
                "{\"title\":\"U\",\"status\":\"publish\",\"content\":\"<!-- wp:paragraph --><p>u</p><!-- /wp:paragraph"
                        + " -->\",\"meta\":{\"wp_pattern_sync_status\":\"unsynced\"}}"));
        if (created.status() == 201) {
            long id = created.json().get("id").asLong();
            String target = api("wp/v2/blocks/" + id);
            send(RestCall.json("alice", "PATCH", target, "{\"meta\":{\"wp_pattern_sync_status\":\"\"}}"));
            send(RestCall.json("alice", "PATCH", target, "{\"meta\":{\"wp_pattern_sync_status\":null}}"));
            send(RestCall.json("alice", "PATCH", target, "{\"wp_pattern_sync_status\":\"unsynced\"}"));
            send(RestCall.get("alice", target + "?context=edit"));
        }
        String unsynced = api("wp/v2/blocks/" + blocks.unsynced());
        send(RestCall.get("alice", unsynced + "?_fields=wp_pattern_sync_status"));
        send(RestCall.get("alice", unsynced + "?_fields=meta,wp_pattern_sync_status"));
        send(RestCall.get("alice", unsynced + "?_fields=id,title"));
        send(RestCall.get("alice", unsynced + "?context=edit"));
    }

    @Test
    void b04CollectionsByContext() throws Exception {
        blocks();
        send(RestCall.get("dave", api("wp/v2/blocks?context=edit")));
        send(RestCall.get("dave", api("wp/v2/blocks?context=view")));
        send(RestCall.get("erin", api("wp/v2/blocks?context=edit")));
        send(RestCall.get("anonymous", api("wp/v2/blocks")));
        send(RestCall.get("carol", api("wp/v2/blocks?context=edit&per_page=100&page=1")));
    }

    @Test
    void b05AnAuthorOnAnotherUsersBlock() throws Exception {
        Blocks blocks = blocks();
        send(RestCall.get("carol", api("wp/v2/blocks/" + blocks.synced())));
        send(RestCall.json("carol", "PATCH", api("wp/v2/blocks/" + blocks.synced()), "{\"title\":\"carol\"}"));
        send(RestCall.get("carol", api("wp/v2/blocks/" + blocks.carols() + "?context=edit")));
    }

    @Test
    void b06CreatePermission() throws Exception {
        Exchange carol = send(options("carol", api("wp/v2/blocks")));
        assertThat(carol.header("Allow"))
                .hasValueSatisfying(allow -> assertThat(allow).contains("GET, POST"));
        send(options("dave", api("wp/v2/blocks")));
        send(options("anonymous", api("wp/v2/blocks")));
        send(RestCall.get("carol", api("wp/v2/blocks")));
        send(RestCall.json(
                "dave",
                "POST",
                api("wp/v2/blocks"),
                "{\"title\":\"D\",\"status\":\"publish\",\"content\":\"" + escaped(PARAGRAPH) + "\"}"));
    }

    @Test
    void b07PatternCategoryTerms() throws Exception {
        Blocks blocks = blocks();
        send(RestCall.get("anonymous", api("wp/v2/wp_pattern_category")));
        Exchange carol = send(RestCall.get("carol", api("wp/v2/wp_pattern_category")));
        assertThat(carol.header("Allow"))
                .hasValueSatisfying(allow -> assertThat(allow).contains("GET, POST"));
        send(RestCall.get("anonymous", api("wp/v2/wp_pattern_category/" + blocks.buttons())));
        send(RestCall.json("dave", "POST", api("wp/v2/wp_pattern_category"), "{\"name\":\"Hero\"}"));
        send(RestCall.json("dave", "POST", api("wp/v2/wp_pattern_category"), "{\"name\":\"Dave\"}"));
        send(RestCall.json("erin", "POST", api("wp/v2/wp_pattern_category"), "{\"name\":\"Erin\"}"));
        send(new RestCall(
                "carol",
                "DELETE",
                api("wp/v2/wp_pattern_category/" + blocks.hero() + "?force=true"),
                Map.of(),
                Optional.empty(),
                Optional.empty()));
        send(new RestCall(
                "bob",
                "DELETE",
                api("wp/v2/wp_pattern_category/" + blocks.hero() + "?force=true"),
                Map.of(),
                Optional.empty(),
                Optional.empty()));
        send(RestCall.get("alice", api("wp/v2/blocks/" + blocks.synced() + "?context=edit")));
    }

    @Test
    void b08PatternRoutesPermission() throws Exception {
        Exchange patterns = send(RestCall.get("dave", api("wp/v2/block-patterns/patterns")));
        assertThat(patterns.status()).isEqualTo(200);
        assertThat(patterns.header("Allow")).hasValue("GET");
        List<String> names = new ArrayList<>();
        JsonNode categoriesOfTheFirst = null;
        for (JsonNode pattern : patterns.json()) {
            if (names.isEmpty()) {
                categoriesOfTheFirst = pattern.get("categories");
            }
            names.add(pattern.path("name").asText());
        }
        assertThat(names.subList(0, Math.min(names.size(), CORE_PATTERNS.size())))
                .containsExactlyElementsOf(CORE_PATTERNS);
        assertThat(categoriesOfTheFirst).containsExactly(TextNode.valueOf("posts"));
        for (String route : List.of("wp/v2/block-patterns/patterns", "wp/v2/block-patterns/categories")) {
            if (route.endsWith("categories")) {
                send(RestCall.get("dave", api(route)));
            }
            send(RestCall.get("erin", api(route)));
            send(RestCall.get("anonymous", api(route)));
            send(RestCall.get("dave", api(route + "?context=foo")));
            send(RestCall.get("dave", api(route + "?_fields=name")));
            send(new RestCall("dave", "HEAD", api(route), Map.of(), Optional.empty(), Optional.empty()));
        }
    }

    @Test
    void b09PatternCategories() throws Exception {
        send(RestCall.get("alice", api("wp/v2/block-patterns/categories")));
    }

    @Test
    void b10WithoutCorePatterns() throws Exception {
        try {
            option(NO_CORE_PATTERNS_SWITCH, "1");
            send(RestCall.get("alice", api("wp/v2/block-patterns/patterns")));
            send(RestCall.get("alice", api("wp/v2/block-patterns/categories")));
        } finally {
            option(NO_CORE_PATTERNS_SWITCH, "0");
        }
    }

    @Test
    void b11RegisteredPatterns() throws Exception {
        try {
            option(PATTERNS_SWITCH, "1");
            send(RestCall.get("alice", api("wp/v2/block-patterns/patterns")));
            send(RestCall.get("alice", api("wp/v2/block-patterns/categories")));
        } finally {
            option(PATTERNS_SWITCH, "0");
        }
    }

    @Test
    void b12BlockTypes() throws Exception {
        send(RestCall.get("dave", api("wp/v2/block-types/core/paragraph")));
        send(RestCall.get("erin", api("wp/v2/block-types/core/paragraph")));
        send(RestCall.get("alice", api("wp/v2/block-types/core/nope")));
        send(RestCall.get("anonymous", api("wp/v2/block-types/core/nope")));
        send(RestCall.get("alice", api("wp/v2/block-types/Core/Paragraph")));
        send(RestCall.get("alice", api("wp/v2/block-types/core/paragraph?context=foo")));
        send(RestCall.get("alice", api("wp/v2/block-types/acme")));
        send(RestCall.get("alice", api("wp/v2/block-types/core?namespace=acme")));
        send(RestCall.get("alice", api("wp/v2/block-types/core/archives")));
        try {
            option(BLOCK_TYPES_SWITCH, "1");
            send(RestCall.get("alice", api("wp/v2/block-types/acme/notice")));
            send(RestCall.get("alice", api("wp/v2/block-types/acme/supports")));
            send(RestCall.get("alice", api("wp/v2/block-types/acme/late")));
            send(RestCall.get("alice", api("wp/v2/block-types/acme")));
        } finally {
            option(BLOCK_TYPES_SWITCH, "0");
        }
        try {
            option(NOTICE_BLOCK_SWITCH, "1");
            send(RestCall.get("alice", api("wp/v2/block-types/qp-it/notice")));
        } finally {
            option(NOTICE_BLOCK_SWITCH, "0");
        }
    }

    @Test
    void b13Renderer() throws Exception {
        long draft = post("bob", "post", "draft", "b13 draft", args());
        long published = post("bob", "post", "publish", "b13 published", args());
        VerificationFacts.note(item, "posts", args().put("bob's draft", draft).put("bob's published", published));
        String archives = "wp/v2/block-renderer/core/archives";
        send(RestCall.get("alice", api(archives)));
        send(RestCall.get("anonymous", api(archives)));
        send(RestCall.get("alice", api(archives + "?context=edit")));
        send(RestCall.get("alice", api("wp/v2/block-renderer/core/paragraph?context=edit")));
        send(RestCall.get("alice", api("wp/v2/block-renderer/acme/nope?context=edit")));
        send(RestCall.get("alice", api("wp/v2/block-renderer/Core/Archives?context=edit")));
        send(RestCall.get("erin", api(archives + "?context=edit")));
        send(RestCall.get("carol", api(archives + "?context=edit&post_id=" + draft)));
        send(RestCall.get("dave", api(archives + "?context=edit&post_id=999999")));
        send(RestCall.get("alice", api(archives + "?context=edit&post_id=abc")));
        send(RestCall.get("alice", api(archives + "?context=edit&" + bracketed("attributes", "foo") + "=1")));
        send(RestCall.get(
                "alice", api(archives + "?context=edit&" + bracketed("attributes", "showLabel") + "=notbool")));
        send(RestCall.json(
                "alice", "POST", api(archives + "?context=edit"), "{\"attributes\":{\"displayAsDropdown\":true}}"));
        // The order of the checks: each request fails two of them; the answer names the one WordPress runs first.
        send(RestCall.get("anonymous", api("wp/v2/block-renderer/acme/nope?context=edit")));
        send(RestCall.get("anonymous", api(archives + "?context=edit&post_id=abc")));
        send(RestCall.get("anonymous", api(archives + "?context=edit&" + bracketed("attributes", "foo") + "=1")));
        send(RestCall.get("erin", api("wp/v2/block-renderer/acme/nope?context=edit&attributes=5")));
        send(RestCall.get("alice", api("wp/v2/block-renderer/acme/nope?context=edit&attributes=5")));
        send(RestCall.get("alice", api("wp/v2/block-renderer/acme/nope")));
        send(RestCall.get("alice", api(archives + "?context=edit&post_id=0")));
        send(RestCall.get("alice", api(archives + "?context=edit&post_id=-1")));
        send(RestCall.get("alice", api(archives + "?context=edit&post_id=" + published)));
        send(new RestCall(
                "alice", "HEAD", api(archives + "?context=edit"), Map.of(), Optional.empty(), Optional.empty()));
        try {
            option(NOTICE_BLOCK_SWITCH, "1");
            send(RestCall.get(
                    "alice",
                    api("wp/v2/block-renderer/qp-it/notice?context=edit&" + bracketed("attributes", "message") + "="
                            + enc("<b>x</b> & y"))));
            send(RestCall.get("alice", api("wp/v2/block-renderer/qp-it/notice?context=edit")));
        } finally {
            option(NOTICE_BLOCK_SWITCH, "0");
        }
    }

    @Test
    void b14OptionsOfTheBlockRoutes() throws Exception {
        Blocks blocks = blocks();
        VerificationFacts.saveOptions("/wp/v2/blocks", send(options("alice", api("wp/v2/blocks"))));
        send(options("alice", api("wp/v2/blocks/" + blocks.synced())));
        VerificationFacts.saveOptions(
                "/wp/v2/wp_pattern_category", send(options("alice", api("wp/v2/wp_pattern_category"))));
        VerificationFacts.saveOptions(
                "/wp/v2/block-types/{namespace}/{name}",
                send(options("alice", api("wp/v2/block-types/core/paragraph"))));
        send(options("alice", api("wp/v2/block-types")));
        VerificationFacts.saveOptions(
                "/wp/v2/block-renderer/{name}", send(options("alice", api("wp/v2/block-renderer/core/archives"))));
        VerificationFacts.saveOptions(
                "/wp/v2/block-patterns/patterns", send(options("alice", api("wp/v2/block-patterns/patterns"))));
        VerificationFacts.saveOptions(
                "/wp/v2/block-patterns/categories", send(options("alice", api("wp/v2/block-patterns/categories"))));
    }

    @Test
    void b15SiteAdministratorWithoutUnfilteredHtml() throws Exception {
        try {
            JsonNode admin = wp.operation("site_admin", args().put("site", sitePath()));
            VerificationFacts.note(item, "site_admin", withoutPassword(admin));
            Exchange created = send(RestCall.json(
                    "sadmin",
                    "POST",
                    api("wp/v2/blocks"),
                    "{\"title\":\"S\",\"status\":\"publish\",\"content\":\"<!-- wp:paragraph --><p>x<script>x</script>"
                            + "</p><!-- /wp:paragraph -->\"}"));
            if (created.status() == 201) {
                send(RestCall.get(
                        "sadmin", api("wp/v2/blocks/" + created.json().get("id").asLong() + "?context=edit")));
            }
            send(options("sadmin", api("wp/v2/blocks")));
        } finally {
            deleteSiteAdmin();
        }
    }

    // ---- editor-environment reference, Part G -------------------------------------------------------------------

    @Test
    void v01ActiveTheme() throws Exception {
        activeTheme();
        VerificationFacts.note(item, "wp_global_styles after the reads", latestPost("wp_global_styles", "publish"));
    }

    /** Every caller's {@code ?status=active} reads, view and edit. */
    void activeTheme() throws Exception {
        for (String as : List.of("bob", "carol", "dave", "erin", "anonymous", "alice")) {
            send(RestCall.get(as, api("wp/v2/themes?status=active")));
            send(RestCall.get(as, api("wp/v2/themes?context=edit&status=active")));
        }
    }

    @Test
    void v02ThemeListsByStatus() throws Exception {
        send(RestCall.get("bob", api("wp/v2/themes")));
        send(RestCall.get("bob", api("wp/v2/themes?status=inactive")));
        send(RestCall.get("bob", api("wp/v2/themes?status=active,inactive")));
        send(RestCall.get("bob", api("wp/v2/themes?" + bracketed("status", "") + "=active")));
        send(RestCall.get("bob", api("wp/v2/themes?status=foo")));
        send(RestCall.get("anonymous", api("wp/v2/themes?status=foo")));
    }

    @Test
    void v03AllThemes() throws Exception {
        send(RestCall.get("alice", api("wp/v2/themes")));
        send(RestCall.get("alice", api("wp/v2/themes?status=inactive")));
    }

    @Test
    void v04SingleThemes() throws Exception {
        send(RestCall.get("bob", api("wp/v2/themes/twentytwentyfive")));
        send(RestCall.get("bob", api("wp/v2/themes/twentytwentyfour")));
        send(RestCall.get("alice", api("wp/v2/themes/twentytwentyfour")));
        send(RestCall.get("alice", api("wp/v2/themes/nope")));
        send(RestCall.get("bob", api("wp/v2/themes/nope")));
        send(RestCall.get("alice", api("wp/v2/themes/quarkpress")));
    }

    @Test
    void v05FieldsOfTheActiveTheme() throws Exception {
        send(RestCall.get("bob", api("wp/v2/themes?status=active&_fields=theme_supports.formats,stylesheet")));
    }

    @Test
    void v06OptionsOfTheThemeRoutes() throws Exception {
        VerificationFacts.saveOptions("/wp/v2/themes", send(options("alice", api("wp/v2/themes"))));
        VerificationFacts.saveOptions(
                "/wp/v2/themes/{stylesheet}", send(options("alice", api("wp/v2/themes/twentytwentyfive"))));
    }

    @Test
    void v07TheClassicTheme() throws Exception {
        Long published = null;
        try {
            option(THEME_SWITCH, "1");
            activeTheme();
            send(options("alice", api("wp/v2/themes")));
            send(options("alice", api("wp/v2/themes/quarkpress")));
            published = post("bob", "post", "publish", "v07 published", args());
            editorPage(login("bob"), published, "edit as bob");
        } finally {
            try {
                option(THEME_SWITCH, "0");
            } finally {
                deletePost(published);
            }
        }
    }

    @Test
    void v08EditorPageAsBob() throws Exception {
        Long published = null;
        try {
            published = post("bob", "post", "publish", "v08 published", args());
            Session bob = login("bob");
            editorPage(bob, published, "edit as bob");
            newPostPage(bob, "new post as bob");
        } finally {
            try {
                deletePost(published);
            } finally {
                deleteNewAutoDrafts();
            }
        }
    }

    @Test
    void v09EditorPageByRole() throws Exception {
        Long published = null;
        Long davesDraft = null;
        try {
            published = post("bob", "post", "publish", "v09 published", args());
            davesDraft = post("dave", "post", "draft", "v09 dave's draft", args());
            editorPage(login("dave"), davesDraft, "edit as dave");
            JsonNode admin = wp.operation("site_admin", args().put("site", sitePath()));
            VerificationFacts.note(item, "site_admin", withoutPassword(admin));
            editorPage(login("sadmin"), published, "edit as sadmin");
            editorPage(login("alice"), published, "edit as alice");
        } finally {
            try {
                deleteSiteAdmin();
            } finally {
                try {
                    deletePost(published);
                } finally {
                    deletePost(davesDraft);
                }
            }
        }
    }

    @Test
    void v10AutosaveOnOpen() throws Exception {
        Long published = null;
        try {
            published = post("bob", "post", "publish", "v10 published", args());
            backdate(published, Duration.ofHours(1));
            JsonNode autosave = wp.operation(
                    "autosave",
                    args().put("site", sitePath())
                            .put("as", "bob")
                            .put("post", published)
                            .put("title", "v10 autosave")
                            .put("content", "v10 newer"));
            VerificationFacts.note(item, "autosave", autosave);
            Session bob = login("bob");
            editorPage(bob, published, "edit with a newer autosave");
            long autosaveId = autosave.path("id").asLong();
            if (autosaveId > 0) {
                backdate(autosaveId, Duration.ofHours(2));
                editorPage(bob, published, "edit with an older autosave");
                send(RestCall.get("bob", api("wp/v2/posts/" + published + "/autosaves?context=edit")));
                VerificationFacts.note(
                        item,
                        "newest revision after the second open (autosave " + autosaveId + ")",
                        latestPost("revision", "inherit"));
            }
        } finally {
            deletePost(published);
        }
    }

    @Test
    void v11OEmbedProxy() throws Exception {
        long published = post("bob", "post", "publish", "v11 published", args());
        long draft = post("bob", "post", "draft", "v11 draft", args());
        String url = "http://localhost" + site + "/?p=" + published;
        String proxy = "oembed/1.0/proxy";
        send(RestCall.get("bob", api(proxy + "?url=" + enc(url))));
        send(RestCall.get("bob", api(proxy + "?url=" + enc(url) + "&maxwidth=100")));
        send(RestCall.get("bob", api(proxy + "?url=" + enc(url) + "&maxwidth=abc")));
        send(RestCall.get("bob", api(proxy)));
        send(RestCall.get("bob", api(proxy + "?url=" + enc(url) + "&format=foo")));
        send(RestCall.get("erin", api(proxy + "?url=" + enc(url))));
        send(RestCall.get("anonymous", api(proxy + "?url=" + enc(url))));
        send(RestCall.get("bob", api(proxy + "?url=" + enc("http://localhost" + site + "/v11-published/"))));
        wp.operation("create_site", args().put("path", "/fv11x/").put("title", "v11x"));
        ObjectNode other = args().put("site", "/fv11x/")
                .put("as", "bob")
                .put("type", "post")
                .put("status", "publish")
                .put("title", "v11 other site");
        other.set("fields", args());
        long elsewhere = wp.operation("post", other).get("id").asLong();
        send(RestCall.get("bob", api(proxy + "?url=" + enc("http://localhost/fv11x/?p=" + elsewhere))));
        VerificationFacts.saveOptions("/oembed/1.0/proxy", send(options("alice", api(proxy))));
        // One worker: WordPress's request to its own host finds nothing on port 80.
        send(RestCall.get("bob", api(proxy + "?url=" + enc("http://localhost" + site + "/?p=" + draft))));
        useLoopback();
        long loopbackDraft = post("bob", "post", "draft", "v11 draft", args());
        long loopbackPublished = post("bob", "post", "publish", "v11 published", args());
        send(RestCall.get("bob", api(proxy + "?url=" + enc("http://localhost" + site + "/?p=" + loopbackDraft))));
        send(RestCall.get("bob", api(proxy + "?url=" + enc("http://localhost" + site + "/?p=" + loopbackPublished))));
    }

    @Test
    void v12OEmbedHandlersOffline() throws Exception {
        String proxy = "oembed/1.0/proxy?url=";
        for (String url : List.of(
                "https://example.com/a.mp3",
                "https://example.com/a.mp4",
                "https://example.com/page",
                "https://www.youtube.com/watch?v=x",
                "https://www.youtube.com/embed/x")) {
            send(RestCall.get("bob", api(proxy + enc(url))));
        }
    }

    @Test
    void v13NoOEmbedTransients() throws Exception {
        for (String path : List.of("/fv11/", "/fv12/")) {
            VerificationFacts.note(
                    item,
                    "options_like " + path,
                    network.operation("options_like", args().put("site", path).put("prefix", "_transient_oembed_")));
        }
        if (loopbackNetwork != null) {
            VerificationFacts.note(
                    item,
                    "options_like /fv11/ (four workers)",
                    loopbackNetwork.operation(
                            "options_like", args().put("site", "/fv11/").put("prefix", "_transient_oembed_")));
        }
    }

    @Test
    void v14OEmbedEmbedRoute() throws Exception {
        long published = post("bob", "post", "publish", "v14 published", args());
        long draft = post("bob", "post", "draft", "v14 draft", args());
        String embed = "oembed/1.0/embed?url=";
        String url = enc("http://localhost" + site + "/?p=" + published);
        send(RestCall.get("anonymous", api(embed + url)));
        send(RestCall.get("anonymous", api(embed + url + "&format=xml")));
        send(RestCall.get("anonymous", api(embed + url + "&format=foo")));
        send(RestCall.get("anonymous", api(embed + enc("http://localhost" + site + "/?p=" + draft))));
    }

    @Test
    void v15UrlModeUploads() throws Exception {
        List<String> written = new ArrayList<>();
        try {
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"https://images.invalid/a.jpg\"}"));
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"https://example.com/a.jpg\"}"));
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"https://example.com/\"}"));
            String photo = writeUpload("qp-v15-photo.jpg", MediaFixtures.jpeg(64, 48), written);
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"" + photo + "\"}"));
            send(RestCall.json(
                    "bob", "POST", api("wp/v2/media"), "{\"url\":\"" + photo + "\",\"generate_sub_sizes\":false}"));
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"http://localhost/nope.jpg\"}"));
            String spaced = writeUpload("qp v15 my photo.jpg", MediaFixtures.jpeg(64, 48), written);
            send(RestCall.json(
                    "bob", "POST", api("wp/v2/media"), "{\"url\":\"" + spaced + "\",\"generate_sub_sizes\":false}"));
            VerificationFacts.note(
                    item, "uploads_listing", wp.operation("uploads_listing", args().put("site", sitePath())));
        } finally {
            removeUploads(written);
        }
    }

    @Test
    void v16UrlModeSizeOnAMultisite() throws Exception {
        List<String> written = new ArrayList<>();
        JsonNode spaceCheck = wp.operation("network_option", args().put("name", "upload_space_check_disabled"))
                .get("value");
        JsonNode maxk = wp.operation("network_option", args().put("name", "fileupload_maxk"))
                .get("value");
        try {
            setNetworkOption("upload_space_check_disabled", TextNode.valueOf("0"));
            VerificationFacts.note(item, "fileupload_maxk", wp.operation("fileupload_maxk", args().put("value", "1")));
            String big = writeUpload(
                    "qp-v16-big.jpg", MediaRestFactsDbTest.padded(MediaFixtures.jpeg(16, 16), 2048), written);
            send(RestCall.json("bob", "POST", api("wp/v2/media"), "{\"url\":\"" + big + "\"}"));
            send(RestCall.json(
                    "bob", "POST", api("wp/v2/media"), "{\"url\":\"" + big + "\",\"generate_sub_sizes\":false}"));
        } finally {
            try {
                ObjectNode restore = args();
                restore.set("value", maxk);
                wp.operation("fileupload_maxk", restore);
            } finally {
                try {
                    setNetworkOption("upload_space_check_disabled", spaceCheck);
                } finally {
                    removeUploads(written);
                }
            }
        }
    }

    @Test
    void v19IndexLinks() throws Exception {
        for (boolean classic : List.of(false, true)) {
            try {
                if (classic) {
                    option(THEME_SWITCH, "1");
                }
                for (String as : List.of("anonymous", "alice", "bob", "carol", "dave", "erin")) {
                    Exchange index = client.send(RestCall.get(as, api("")));
                    ObjectNode summary = args();
                    summary.put("status", index.status());
                    ArrayNode keys = summary.putArray("keys");
                    fieldNames(index.json()).forEach(keys::add);
                    summary.set("_links", index.json().get("_links"));
                    VerificationFacts.note(
                            item, "index as " + as + (classic ? " (classic theme)" : " (Twenty Twenty-Five)"), summary);
                }
            } finally {
                if (classic) {
                    option(THEME_SWITCH, "0");
                }
            }
        }
    }

    @Test
    void v20RouteKeys() throws Exception {
        Exchange wpV2 = client.send(RestCall.get("alice", api("wp/v2")));
        ArrayNode all = OracleJson.MAPPER.createArrayNode();
        ArrayNode slice = OracleJson.MAPPER.createArrayNode();
        for (String route : fieldNames(wpV2.json().get("routes"))) {
            all.add(route);
            for (String part : List.of(
                    "/blocks",
                    "/block-types",
                    "/block-renderer",
                    "/block-patterns",
                    "/themes",
                    "/wp_pattern_category")) {
                if (route.startsWith("/wp/v2" + part)) {
                    slice.add(route);
                    break;
                }
            }
        }
        VerificationFacts.note(item, "wp/v2 route keys (alice)", all);
        VerificationFacts.note(item, "wp/v2 route keys of the slice (alice)", slice);
        Exchange oembed = send(RestCall.get("alice", api("oembed/1.0")));
        if (oembed.status() == 200) {
            ArrayNode keys = OracleJson.MAPPER.createArrayNode();
            fieldNames(oembed.json().get("routes")).forEach(keys::add);
            VerificationFacts.note(item, "oembed/1.0 route keys (alice)", keys);
        }
    }

    // ---- carries --------------------------------------------------------------------------------------------------

    @Test
    void c01RefusedMetaKeyOfARestName() throws Exception {
        try {
            option(REST_NAME_META_SWITCH, "1");
            long post = post("bob", "post", "publish", "c01 post", args());
            send(RestCall.json("bob", "POST", api("wp/v2/posts/" + post), "{\"meta\":{\"qp_public\":\"x\"}}"));
            send(RestCall.get("bob", api("wp/v2/posts/" + post + "?context=edit&_fields=id,meta")));
        } finally {
            option(REST_NAME_META_SWITCH, "0");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The item site's path as the setup operations take it ({@code /fb01/}, {@code /} for the main site). */
    String sitePath() {
        return site + "/";
    }

    /** A target under the item site's API root ({@code /fb01/wp-json/<route>}). */
    String api(String route) {
        return site + "/wp-json/" + route;
    }

    /** Sends the call and records the exchange under the running item. */
    Exchange send(RestCall call) throws Exception {
        Exchange exchange = client.send(call);
        if (item != null) {
            VerificationFacts.record(item, exchange);
        }
        return exchange;
    }

    static RestCall options(String as, String target) {
        return new RestCall(as, "OPTIONS", target, Map.of(), Optional.empty(), Optional.empty());
    }

    void option(String name, String value) throws Exception {
        wp.operation("option", args().put("site", sitePath()).put("name", name).put("value", value));
    }

    void setNetworkOption(String name, JsonNode value) throws Exception {
        ObjectNode input = args().put("name", name);
        input.set("value", value == null ? JsonNodeFactory.instance.nullNode() : value);
        VerificationFacts.note(item, "set_network_option " + name, wp.operation("set_network_option", input));
    }

    /** Inserts a post on the item site (rest-setup.php {@code post}); answers its id. */
    long post(String as, String type, String status, String title, ObjectNode fields) throws Exception {
        ObjectNode input = args().put("site", sitePath())
                .put("as", as)
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return wp.operation("post", input).get("id").asLong();
    }

    void deletePost(Long id) throws Exception {
        if (id != null) {
            wp.operation(
                    "delete_post", args().put("site", sitePath()).put("id", id).put("force", true));
        }
    }

    JsonNode latestPost(String type, String status) throws Exception {
        return wp.operation(
                "latest_post", args().put("site", sitePath()).put("type", type).put("status", status));
    }

    /** Deletes the auto-drafts a new-post page made on the main site (none of another item is left by then). */
    void deleteNewAutoDrafts() throws Exception {
        for (int i = 0; i < 5; i++) {
            long id = latestPost("post", "auto-draft").path("id").asLong();
            if (id == 0) {
                return;
            }
            deletePost(id);
        }
    }

    /** Sets the post's modified time (and its date) to {@code ago} before now (rest-setup.php {@code backdate}). */
    void backdate(long id, Duration ago) throws Exception {
        String at = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now().minus(ago));
        ObjectNode input = args().put("site", sitePath());
        input.putArray("posts").addObject().put("id", id).put("at", at).put("date", false);
        VerificationFacts.note(item, "backdate " + id, wp.operation("backdate", input));
    }

    /**
     * The blocks and pattern categories the blocks reference's examples use: a synced block of alice's ({@code 35}),
     * one of carol's ({@code 41}) and an unsynced one of alice's ({@code 42}), all published, and the pattern
     * categories "Hero" ({@code 7}) and "Buttons" ({@code 8}) created through REST by alice.
     */
    Blocks blocks() throws Exception {
        long synced = post("alice", "wp_block", "publish", "HAR pattern", fields("post_content", PARAGRAPH));
        long carols = post("carol", "wp_block", "publish", "Carol pattern", fields("post_content", PARAGRAPH));
        long unsynced = post("alice", "wp_block", "publish", "Unsynced pattern", fields("post_content", PARAGRAPH));
        wp.operation(
                "set_meta",
                args().put("site", sitePath())
                        .put("post", unsynced)
                        .put("key", "wp_pattern_sync_status")
                        .put("value", "unsynced"));
        long hero =
                created(send(RestCall.json("alice", "POST", api("wp/v2/wp_pattern_category"), "{\"name\":\"Hero\"}")));
        long buttons = created(
                send(RestCall.json("alice", "POST", api("wp/v2/wp_pattern_category"), "{\"name\":\"Buttons\"}")));
        Blocks blocks = new Blocks(synced, carols, unsynced, hero, buttons);
        VerificationFacts.note(
                item,
                "fixtures",
                args().put("35 (alice, synced)", synced)
                        .put("41 (carol, synced)", carols)
                        .put("42 (alice, unsynced)", unsynced)
                        .put("7 Hero", hero)
                        .put("8 Buttons", buttons));
        return blocks;
    }

    /** The id of a 201 answer; a setup request that fails ends the item with what WordPress said. */
    static long created(Exchange exchange) {
        assertThat(exchange.status())
                .as("setup request %s answered %s", exchange.call().target(), exchange.text())
                .isEqualTo(201);
        return exchange.json().get("id").asLong();
    }

    /** Writes a file into the main site's uploads (served by php -S as is); answers its URL. */
    String writeUpload(String name, byte[] bytes, List<String> written) throws Exception {
        JsonNode answer = wp.operation(
                "write_upload",
                args().put("site", "/")
                        .put("name", name)
                        .put("bytes", Base64.getEncoder().encodeToString(bytes)));
        written.add(name);
        VerificationFacts.note(item, "write_upload " + name, answer);
        return answer.get("url").asText();
    }

    void removeUploads(List<String> written) throws Exception {
        for (String name : written) {
            wp.operation("remove_upload", args().put("site", "/").put("file", name));
        }
    }

    void deleteSiteAdmin() throws Exception {
        wp.operation("delete_user", args().put("login", "sadmin"));
    }

    Session login(String user) throws Exception {
        JsonNode answer = wp.operation("cookie_session", args().put("user", user));
        StringBuilder cookie = new StringBuilder();
        for (JsonNode entry : answer.get("cookies")) {
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append(entry.get("name").asText())
                    .append('=')
                    .append(entry.get("value").asText());
        }
        return new Session(
                user,
                cookie.toString(),
                answer.get("rest_nonce").asText(),
                answer.get("session").asText());
    }

    static RestCall asSession(Session session, RestCall call) {
        return call.withHeader("Cookie", session.cookie());
    }

    /** Opens the post's editor page in the session and notes what its inline scripts hand the editor. */
    void editorPage(Session session, long post, String label) throws Exception {
        Exchange page = send(
                asSession(session, RestCall.get(session.user(), "/wp-admin/post.php?post=" + post + "&action=edit")));
        noteEditorPage(label, page);
    }

    /** Opens the new-post page in the session and notes what its inline scripts hand the editor. */
    void newPostPage(Session session, String label) throws Exception {
        Exchange page = send(asSession(session, RestCall.get(session.user(), "/wp-admin/post-new.php")));
        noteEditorPage(label, page);
    }

    /**
     * Notes, read out of the page by marker text: the preload map, the block categories, the server block definitions
     * (their count, and whole those whose {@code variations} list is not empty), the editor settings and the initial
     * edits of {@code initializeEditor}.
     */
    void noteEditorPage(String label, Exchange page) {
        String html = page.text();
        ObjectNode found = args();
        found.put("status", page.status());
        found.set(
                "preload", firstValue(html, "createPreloadingMiddleware(", '{').orElse(null));
        found.set(
                "categories", firstValue(html, "wp.blocks.setCategories(", '[').orElse(null));
        Optional<JsonNode> definitions = firstValue(html, "unstable__bootstrapServerSideBlockDefinitions(", '{');
        definitions.ifPresent(all -> {
            found.put("definitionCount", all.size());
            ObjectNode withVariations = found.putObject("definitionsWithVariations");
            for (Map.Entry<String, JsonNode> entry : all.properties()) {
                if (!entry.getValue().path("variations").isEmpty()) {
                    withVariations.set(entry.getKey(), entry.getValue());
                }
            }
        });
        int at = html.indexOf("initializeEditor(");
        if (at >= 0) {
            int open = html.indexOf('{', at);
            Optional<Parsed> settings = parseAt(html, open);
            settings.ifPresent(parsed -> {
                found.set("settings", parsed.value());
                ArrayNode keys = found.putArray("settingsKeys");
                fieldNames(parsed.value()).forEach(keys::add);
                found.put("initializeEditorArguments", html.substring(at, Math.min(html.length(), open)));
                parseAt(html, html.indexOf('{', parsed.end()))
                        .ifPresent(edits -> found.set("initialEdits", edits.value()));
            });
        }
        VerificationFacts.note(item, label, found);
    }

    /** A JSON value parsed out of a page and the offset just after it. */
    record Parsed(JsonNode value, int end) {}

    /** The JSON value starting at the first {@code open} after {@code marker}; empty when there is none. */
    static Optional<JsonNode> firstValue(String text, String marker, char open) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return Optional.empty();
        }
        return parseAt(text, text.indexOf(open, at)).map(Parsed::value);
    }

    /** The JSON value starting at {@code start} (anything after it is ignored); empty when it does not parse. */
    static Optional<Parsed> parseAt(String text, int start) {
        if (start < 0) {
            return Optional.empty();
        }
        try (JsonParser parser = OracleJson.MAPPER.createParser(text.substring(start))) {
            JsonNode value = parser.readValueAsTree();
            return Optional.of(
                    new Parsed(value, start + (int) parser.currentLocation().getCharOffset()));
        } catch (IOException notJson) {
            return Optional.empty();
        }
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static ObjectNode fields(String name, String value) {
        return args().put(name, value);
    }

    static JsonNode withoutPassword(JsonNode answer) {
        ObjectNode copy = answer.deepCopy();
        copy.remove("password");
        return copy;
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** {@code name[key]} with the brackets percent-encoded, as a browser sends a nested query parameter. */
    static String bracketed(String name, String key) {
        return name + "%5B" + key + "%5D";
    }

    /** The text as the inside of a JSON string. */
    static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
