package org.quarkword.it.restwp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.wporacle.OracleJson;
import org.quarkword.testing.wprestparity.Exchange;
import org.quarkword.testing.wprestparity.RestCall;
import org.quarkword.testing.wprestparity.RestClient;
import org.quarkword.testing.wprestparity.WordPressRestSite;
import org.quarkword.testing.wpscenario.ScenarioDatabase;
import org.quarkword.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress 7.1.3 answers on its front end for the routing reference's verification items (G1, G2, G3,
 * G14, G20), for the rewrite table's example paths, and for the query reference's items on a plain-permalink site
 * (FQ-G1 to FQ-G9, FQ-G14, FQ-G15, FQ-G22, FQ-G23), for the main query's items with logged-in visitors (FQ-G10 to
 * FQ-G12, FQ-G16 to FQ-G18, FQ-G24, FR's G5, G6 and G13, the content-page and {@code error} answers of the 404
 * decision), and for MySQL's {@code WEEK} numbers, into {@code target/wp-rest-parity/front-facts.json}: status,
 * {@code Location}, {@code Cache-Control}, {@code Expires}, {@code Content-Type}, {@code Link}, {@code X-Redirect-By},
 * {@code X-Robots-Tag}, the first 300 bytes of the body, the document title and the listed post titles of every
 * request. The cast lives on subdirectory sites of the parity network (one per permalink structure); redirects are
 * not followed. Nothing about QuarkWord is asserted.
 * Runs only with {@code -Pwp-parity -Dfront.facts=true}: the property keeps it out of the parity profile's full run.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@EnabledIfSystemProperty(named = "front.facts", matches = "true")
class FrontFactsDbTest {

    static final Path FACTS = Path.of("target", "wp-rest-parity", "front-facts.json");
    static final int BODY_BYTES = 300;
    static final List<String> HEADERS =
            List.of("Location", "Cache-Control", "Expires", "Content-Type", "Link", "X-Redirect-By", "X-Robots-Tag");

    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;
    static final ObjectNode items = OracleJson.MAPPER.createObjectNode();
    static final ObjectNode notes = OracleJson.MAPPER.createObjectNode();

    /** The default-structure site's cast. */
    static String dated;

    static long hello;
    static long second;
    static long about;
    static long team;
    static long dune;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        site = WordPressRestSite.start(
                WordPressTree.ensure(System.getProperty("wp.version")), database, System.getProperty("wp.php", "php"));
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        notes.put(
                "environment",
                "WordPress 7.1.3 served by php -S through the parity router: every non-file path runs index.php with"
                        + " the raw REQUEST_URI and no PATH_INFO; sites are subdirectory sites of the network"
                        + " (home http://localhost/<path>), so the host-root-only rules are absent on them.");
        dated = createSite("gdef", "/%year%/%monthnum%/%day%/%postname%/");
        hello = post(dated, "post", "publish", "Hello world", "hello-world", "2025-11-20 10:15:30", 0, null);
        long news = term(dated, "categories", "{\"name\":\"News\",\"slug\":\"news\"}");
        long releases =
                term(dated, "categories", "{\"name\":\"Releases\",\"slug\":\"releases\",\"parent\":" + news + "}");
        long java = term(dated, "tags", "{\"name\":\"Java\",\"slug\":\"java\"}");
        ObjectNode terms = OracleJson.MAPPER.createObjectNode();
        terms.putArray("category").add(news).add(releases);
        terms.putArray("post_tag").add(java);
        second = post(dated, "post", "publish", "Second post", "second-post", "2025-11-23 08:00:00", 0, terms);
        post(dated, "attachment", "inherit", "Photo", "photo", "2025-11-23 08:30:00", second, null);
        post(dated, "attachment", "inherit", "Seven", "7", "2025-11-23 08:30:00", second, null);
        about = post(dated, "page", "publish", "About", "about", "2025-11-20 10:00:00", 0, null);
        team = post(dated, "page", "publish", "Team", "team", "2025-11-20 10:00:00", about, null);
        post(dated, "attachment", "inherit", "Team photo", "team-photo", "2025-11-23 08:30:00", team, null);
        dune = post(dated, "book", "publish", "Dune", "dune", "2025-11-23 08:00:00", 0, null);
    }

    @AfterAll
    static void stop() {
        try {
            if (items.size() > 0) {
                ObjectNode facts = OracleJson.MAPPER.createObjectNode();
                facts.set("items", items);
                facts.set("notes", notes);
                Files.createDirectories(FACTS.getParent());
                OracleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(FACTS.toFile(), facts);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (site != null) {
                site.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    @Test
    void g01PlainModeIgnoresThePath() throws Exception {
        String plain = createSite("gplain", "");
        long post = post(plain, "post", "publish", "Hello world", "hello-world", "2025-11-20 10:15:30", 0, null);
        record("G1", plain + "/anything/");
        record("G1", plain + "/anything/?p=" + post);
        record("G1", plain + "/?p=" + post);
        record("G1", plain + "/2025/11/20/hello-world/");
        record("G1", plain + "/?p=" + post + "&error=404");
    }

    @Test
    void g02MisCasedAndAccentedSlugsInTheGuess() throws Exception {
        record("G2", dated + "/2025/11/20/HELLO-WO/");
        record("G2", dated + "/2025/11/20/h%C3%A9llo/");
        record("G2", dated + "/2025/11/20/hello/");
    }

    @Test
    void g03MisCasedPathsAreServedWithoutRedirect() throws Exception {
        record("G3", dated + "/About/Team/");
        record("G3", dated + "/about/team/");
        record("G3", dated + "/2025/11/20/Hello-World/");
        record("G3", dated + "/2025/11/20/hello-world/");
    }

    @Test
    void g14IndexPhpPrefixOnANonPathinfoSite() throws Exception {
        record("G14", dated + "/index.php/2025/11/20/hello-world/");
        record("G14", dated + "/index.php");
        record("G14", dated + "/index.php/wp-json/wp/v2/posts");
    }

    @Test
    void g20SubdirectorySites() throws Exception {
        String site2 = createSite("site2", "/%postname%/");
        post(site2, "post", "publish", "Hello world", "hello-world", "2025-11-20 10:15:30", 0, null);
        post(site2, "page", "publish", "About", "about", "2025-11-20 10:00:00", 0, null);
        record("G20", site2 + "/robots.txt");
        record("G20", "/robots.txt");
        record("G20", site2 + "/hello-world/");
        record("G20", "/SITE2/hello-world/");
        record("G20", "/Site2/about/");
        record("G20", site2 + "/wp-json/wp/v2/posts");
    }

    @Test
    void fr06ExamplePathsOfTheDefaultStructure() throws Exception {
        for (String path : List.of(
                "/2025/11/20/hello-world/",
                "/2025/11/20/hello-world/2/",
                "/2025/11/20/hello-world/page/3/",
                "/2025/11/23/second-post/photo/",
                "/2025/11/23/second-post/attachment/7/",
                "/2025/11/23/",
                "/2025/11/",
                "/2025/",
                "/2025/page/2/",
                "/category/news/releases/",
                "/category/news/page/2/",
                "/tag/java/",
                "/author/alice/page/2/",
                "/author/alice/",
                "/search/java%2021/",
                "/search/java+21/",
                "/search/a/b/",
                "/page/2/",
                "/feed/",
                "/comments/feed/",
                "/books/",
                "/books/page/2/",
                "/books/dune/",
                "/books/dune/2/",
                "/about/team/",
                "/about/team/2/",
                "/about/team/team-photo/",
                "/about/team/attachment/team-photo/",
                "/wp-json/wp/v2/posts",
                "/robots.txt",
                "/favicon.ico",
                "/sitemap.xml",
                "/wp-sitemap.xml",
                "/2025/11/20/hello-world/trackback/",
                "/2025/11/20/hello-world/embed/",
                "/2025/11/20/hello-world/comment-page-2/",
                "/wp-register.php",
                "/feed/wp-rss2.php",
                "/",
                "/index.php",
                "/no-such-thing/",
                "/a/b/c/d/",
                "/2025/11/20/hello-world/.",
                "/2025/11/20/hello-world/0/",
                "/?error=404",
                "/?post_type=any",
                "/?post_type=page&page_id=" + team,
                "/?book=dune",
                "/?p=" + hello,
                "/?page_id=" + team,
                "/?p=" + hello + "&posts_per_page=1&utm_source=x",
                "/2025/11/20/hello-world/?page=2",
                "/tag/java/?tag=php",
                "/?taxonomy=wp_pattern_category&term=hero")) {
            record("FR6", dated + path);
        }
    }

    /** The query reference's cast on a plain-permalink site, and its Part G items this slice answers. */
    @Test
    void fqQueryItemsOnThePlainSite() throws Exception {
        String site = createSite("gq", "");
        long news = term(site, "categories", "{\"name\":\"News\",\"slug\":\"news\"}");
        long europe = term(site, "categories", "{\"name\":\"Europe\",\"slug\":\"europe\",\"parent\":" + news + "}");
        long cars = term(site, "tags", "{\"name\":\"Cars\",\"slug\":\"cars\"}");
        long paint = term(site, "tags", "{\"name\":\"Paint\",\"slug\":\"paint\"}");
        long food = term(site, "tags", "{\"name\":\"Food\",\"slug\":\"food\"}");
        long longStory = post(site, "post", "publish", "Long story", "long-story", "2026-07-10 08:00:00", 0, null);
        castPost(site, "publish", "Secret recipe", "secret-recipe", "2026-06-01 08:00:00", terms(0, food), "s3", false);
        castPost(
                site,
                "publish",
                "Car painted red",
                "car-painted-red",
                "2026-05-20 08:00:00",
                terms(europe, cars, paint),
                null,
                false);
        long redCar = castPost(
                site,
                "publish",
                "Red car for sale",
                "red-car-for-sale",
                "2026-04-02 08:00:00",
                terms(news, cars),
                null,
                false);
        castPost(site, "publish", "Spring notes", "spring-notes", "2026-03-14 08:00:00", terms(news), null, false);
        castPost(site, "publish", "Hello world!", "hello-world", "2026-01-05 08:00:00", null, null, false);
        castPost(site, "publish", "Welcome", "welcome", "2025-12-01 08:00:00", null, null, true);
        castPost(site, "draft", "Travel diary", "travel-diary", "2026-02-01 08:00:00", null, null, false);
        castPost(site, "future", "Launch day", "launch-day", "2027-01-01 08:00:00", null, null, false);
        castPost(site, "private", "Board minutes", "board-minutes", "2026-02-15 08:00:00", terms(news), null, false);
        post(site, "attachment", "inherit", "Photo", "photo", "2026-04-02 08:30:00", redCar, null);
        long aboutPage = post(site, "page", "publish", "About", "about", "2026-01-02 08:00:00", 0, null);
        post(site, "page", "publish", "Team", "team", "2026-01-02 08:00:00", aboutPage, null);
        long blogPage = post(site, "page", "publish", "Blog", "blog", "2026-01-02 08:00:00", 0, null);
        long homePage = post(site, "page", "publish", "Home", "home", "2026-01-02 08:00:00", 0, null);
        post(site, "page", "publish", "Privacy Policy", "privacy-policy", "2026-01-02 08:00:00", 0, null);
        notes.put(
                "FQ-G cast",
                "site /gq/ (plain permalinks): posts long-story " + longStory + ", red-car-for-sale " + redCar
                        + "; pages about " + aboutPage + ", blog " + blogPage + ", home " + homePage
                        + "; categories news " + news + ", europe " + europe + "; tags cars " + cars + ", paint "
                        + paint + ", food " + food + "; every post by alice.");

        record("FQ-G1", site + "/?s=");
        record("FQ-G2", site + "/?p=-1");
        record("FQ-G2", site + "/?p=abc");
        record("FQ-G3", site + "/?year=2026&monthnum=2&day=30");
        record("FQ-G3", site + "/?day=40");
        record("FQ-G3", site + "/?monthnum=13");
        record("FQ-G4", site + "/?m=20265");
        record("FQ-G4", site + "/?m=202613");
        record("FQ-G5", site + "/?tag_id=" + cars);
        record("FQ-G5", site + "/?category_name=news");
        record("FQ-G8", site + "/?cat=-" + news);
        record("FQ-G9", site + "/?tag=cars,food");
        record("FQ-G9", site + "/?tag=cars+paint");
        record("FQ-G9", site + "/?tag=cars%2Bpaint");
        record("FQ-G9", site + "/?tag=cars+food");
        record("FQ-G15", site + "/?post_type=any");
        record("FQ-G15", site + "/?post_type=attachment");
        record("FQ-G22", site + "/?pagename=team");
        record("FQ-G22", site + "/?pagename=about/team");
        record("FQ-G22", site + "/?pagename=ABOUT");
        record("FQ-G23", site + "/?p=" + longStory + "&page_id=" + aboutPage);

        option(site, "show_on_front", TextNode.valueOf("page"));
        option(site, "page_on_front", TextNode.valueOf(Long.toString(homePage)));
        option(site, "page_for_posts", TextNode.valueOf(Long.toString(blogPage)));
        record("FQ-G6", site + "/");
        record("FQ-G6", site + "/?paged=2");
        record("FQ-G6", site + "/?pagename=blog");
        record("FQ-G6", site + "/?pagename=blog&paged=2");
        option(site, "wp_page_for_privacy_policy", TextNode.valueOf(Long.toString(homePage)));
        record("FQ-G7", site + "/");
        record("FQ-G7", site + "/?pagename=home");
        option(site, "wp_page_for_privacy_policy", TextNode.valueOf("0"));
        option(site, "page_on_front", TextNode.valueOf(Long.toString(blogPage)));
        record("FQ-G14", site + "/");
        record("FQ-G14", site + "/?paged=2");
        option(site, "show_on_front", TextNode.valueOf("posts"));
    }

    @Test
    void fqMainQueryItemsWithLoggedInVisitors() throws Exception {
        String site = createSite("gm", "");
        long news = term(site, "categories", "{\"name\":\"News\",\"slug\":\"news\"}");
        long europe = term(site, "categories", "{\"name\":\"Europe\",\"slug\":\"europe\",\"parent\":" + news + "}");
        long cars = term(site, "tags", "{\"name\":\"Cars\",\"slug\":\"cars\"}");
        long paint = term(site, "tags", "{\"name\":\"Paint\",\"slug\":\"paint\"}");
        long food = term(site, "tags", "{\"name\":\"Food\",\"slug\":\"food\"}");
        long longStory = post(site, "post", "publish", "Long story", "long-story", "2026-07-10 08:00:00", 0, null);
        FrontFactsDbTest.site.operation(
                "update_post",
                args().put("site", site + "/")
                        .put("as", "alice")
                        .put("id", longStory)
                        .set(
                                "fields",
                                args().put(
                                                "post_content",
                                                "Intro<!--more-->Rest of one\n<!--nextpage-->\nTwo<!--nextpage-->Three")));
        castPostAs(
                site,
                "carol",
                "publish",
                "Secret recipe",
                "secret-recipe",
                "2026-06-01 08:00:00",
                terms(0, food),
                "s3",
                false);
        castPostAs(
                site,
                "bob",
                "publish",
                "Car painted red",
                "car-painted-red",
                "2026-05-20 08:00:00",
                terms(europe, cars, paint),
                null,
                false);
        castPostAs(
                site,
                "carol",
                "publish",
                "Red car for sale",
                "red-car-for-sale",
                "2026-04-02 08:00:00",
                terms(news, cars),
                null,
                false);
        castPostAs(
                site,
                "bob",
                "publish",
                "Spring notes",
                "spring-notes",
                "2026-03-14 08:00:00",
                terms(news),
                null,
                false);
        long hello = castPostAs(
                site, "alice", "publish", "Hello world!", "hello-world", "2026-01-05 08:00:00", null, null, false);
        castPostAs(site, "alice", "publish", "Welcome", "welcome", "2025-12-01 08:00:00", null, null, true);
        long travel = castPostAs(
                site, "carol", "draft", "Travel diary", "travel-diary", "2026-02-01 08:00:00", null, null, false);
        long launch =
                castPostAs(site, "bob", "future", "Launch day", "launch-day", "2027-01-01 08:00:00", null, null, false);
        long aliceDraft = castPostAs(
                site, "alice", "draft", "Alice draft", "alice-draft", "2026-02-02 08:00:00", null, null, false);
        long draftShot = postAs(
                site, "carol", "attachment", "inherit", "Draft shot", "draft-shot", "2026-02-01 08:30:00", travel);
        long aliceShot = postAs(
                site, "alice", "attachment", "inherit", "Alice shot", "alice-shot", "2026-02-01 08:31:00", travel);
        long carolShot = postAs(
                site, "carol", "attachment", "inherit", "Carol shot", "carol-shot", "2026-02-02 08:30:00", aliceDraft);
        long outsider = FrontFactsDbTest.site
                .operation("outsider", args().put("login", "olga"))
                .get("id")
                .longValue();
        notes.put(
                "FQ main query cast",
                "site /gm/ (plain permalinks): long-story " + longStory + " (three content pages), hello-world " + hello
                        + "; drafts travel-diary " + travel + " (carol) and alice-draft " + aliceDraft
                        + " (alice), scheduled launch-day " + launch + " (bob); attachments draft-shot " + draftShot
                        + " (by carol, of carol's draft), alice-shot " + aliceShot
                        + " (by alice, of carol's draft), carol-shot " + carolShot
                        + " (by carol, of alice's draft); erin " + FrontFactsDbTest.site.userId("erin")
                        + " has no posts; olga " + outsider + " is a network user of no site.");

        Session carol = login("carol");
        Session bob = login("bob");
        Session erin = login("erin");
        record("FQ-G10", site + "/?attachment_id=" + draftShot);
        record("FQ-G10", site + "/?attachment_id=" + draftShot, carol);
        record("FQ-G10", site + "/?attachment_id=" + draftShot, erin);
        record("FQ-G10", site + "/?attachment_id=" + draftShot, bob);
        record("FQ-G10", site + "/?attachment_id=" + aliceShot, carol);
        record("FQ-G10", site + "/?attachment_id=" + carolShot, carol);
        record("FQ-G11", site + "/?p=" + travel, carol);
        record("FQ-G11", site + "/?p=" + travel, erin);
        record("FQ-G11", site + "/?p=" + launch, bob);
        record("FQ-G11", site + "/?p=" + launch, carol);
        record("FQ-G12", site + "/?s=secret");
        record("FQ-G12", site + "/?s=secret", erin);
        record("FQ-G16", site + "/?s=red");
        record("FQ-G16", site + "/?s=red%20car");
        record("FQ-G17", site + "/?author=" + FrontFactsDbTest.site.userId("erin"));
        record("FQ-G17", site + "/?author=99999");
        record("FQ-G17", site + "/?author_name=nobody");
        record("FQ-G17", site + "/?author=" + outsider);
        record("FQ-G17", site + "/?author_name=olga");
        record("FQ-G17", site + "/?author_name=erin");
        option(site, "posts_per_page", TextNode.valueOf("3"));
        record("FQ-G18", site + "/");
        record("FQ-G18", site + "/?paged=2");
        record("FQ-G18", site + "/?paged=3");
        record("FQ-G18", site + "/?paged=4");
        option(site, "posts_per_page", TextNode.valueOf("10"));
        record("FQ-G24", site + "/?feed=rss2");
        record("FR15-page", site + "/?p=" + longStory + "&page=2");
        record("FR15-page", site + "/?p=" + longStory + "&page=3");
        record("FR15-page", site + "/?p=" + longStory + "&page=4");
        record("FR15-page", site + "/?p=" + hello + "&page=2");
        record("FR15-page", site + "/?p=" + hello + "&page=0");
        record("FR16-error", site + "/?error=403");
        record("FR16-error", site + "/?error=404");
        record("FR16-error", site + "/?error=410");
        record("FR16-error", site + "/?error=418");
        record("FR16-error", site + "/?error=500");
        record("FR16-error", site + "/?p=-1&error=410");
    }

    @Test
    void frAuthorsAttachmentsOfDraftsAndPageZeroOnTheDatedSite() throws Exception {
        String outsider = "otto";
        long otto = site.operation("outsider", args().put("login", outsider))
                .get("id")
                .longValue();
        long draft =
                post(dated, "post", "draft", "Draft with photo", "draft-with-photo", "2025-11-24 08:00:00", 0, null);
        long shot =
                post(dated, "attachment", "inherit", "Draft photo", "draft-photo", "2025-11-24 08:30:00", draft, null);
        notes.put(
                "FR G5 G6 G13 cast",
                "site /gdef/: draft " + draft + " with attachment " + shot + " (pages off); otto " + otto
                        + " is a network user of no site; erin is a member without posts.");
        record("G5", dated + "/author/" + outsider + "/");
        record("G5", dated + "/author/erin/");
        record("G5", dated + "/?author=" + otto);
        record("G5", dated + "/?author=" + site.userId("erin"));
        record("G6", dated + "/?attachment_id=" + shot);
        record("G6", dated + "/?attachment_id=" + shot, login("alice"));
        record("G13", dated + "/2025/11/23/second-post/0/");
        record("G13", dated + "/2025/11/23/second-post/2/");
    }

    @Test
    void mysqlWeekNumbers() throws Exception {
        try (Connection connection = database.connect("mysql");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT WEEK('2026-05-20', 0), WEEK('2026-05-20', 1),"
                        + " WEEK('2026-01-04', 0), WEEK('2026-01-04', 1), WEEK('2027-01-01', 1),"
                        + " WEEK(DATE_SUB('2026-01-04', INTERVAL 3 DAY), 0), WEEK('2025-05-21', 0),"
                        + " WEEK('2025-05-21', 1), VERSION()")) {
            row.next();
            ArrayNode list = items.putArray("DC3-WEEK");
            ObjectNode entry = list.addObject();
            entry.put(
                    "request",
                    "SELECT WEEK('2026-05-20', 0), WEEK('2026-05-20', 1), WEEK('2026-01-04', 0), WEEK('2026-01-04', 1),"
                            + " WEEK('2027-01-01', 1), WEEK(DATE_SUB('2026-01-04', INTERVAL 3 DAY), 0),"
                            + " WEEK('2025-05-21', 0), WEEK('2025-05-21', 1)");
            ArrayNode values = entry.putArray("values");
            for (int column = 1; column <= 8; column++) {
                values.add(row.getInt(column));
            }
            entry.put("server", row.getString(9));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A login session's cookies. */
    record Session(String user, String cookie) {}

    static Session login(String user) throws Exception {
        JsonNode answer = site.operation("cookie_session", args().put("user", user));
        StringBuilder cookie = new StringBuilder();
        for (JsonNode entry : answer.get("cookies")) {
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append(entry.get("name").asText())
                    .append('=')
                    .append(entry.get("value").asText());
        }
        return new Session(user, cookie.toString());
    }

    /** A post of type {@code post} by {@code as} with the reference's extras: terms, a password, stickiness. */
    static long castPostAs(
            String sitePath,
            String as,
            String status,
            String title,
            String slug,
            String date,
            ObjectNode terms,
            String password,
            boolean sticky)
            throws Exception {
        ObjectNode fields = args().put("post_name", slug).put("post_date", date);
        if (terms != null) {
            fields.set("terms", terms);
        }
        if (password != null) {
            fields.put("post_password", password);
        }
        if (sticky) {
            fields.put("sticky", true);
        }
        ObjectNode input = args().put("site", sitePath + "/")
                .put("as", as)
                .put("type", "post")
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    static long postAs(
            String sitePath, String as, String type, String status, String title, String slug, String date, long parent)
            throws Exception {
        ObjectNode fields = args().put("post_name", slug).put("post_date", date);
        if (parent > 0) {
            fields.put("post_parent", parent);
        }
        ObjectNode input = args().put("site", sitePath + "/")
                .put("as", as)
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    /** A post of type {@code post} by alice with the reference's extras: terms, a password, stickiness. */
    static long castPost(
            String sitePath,
            String status,
            String title,
            String slug,
            String date,
            ObjectNode terms,
            String password,
            boolean sticky)
            throws Exception {
        ObjectNode fields = args().put("post_name", slug).put("post_date", date);
        if (terms != null) {
            fields.set("terms", terms);
        }
        if (password != null) {
            fields.put("post_password", password);
        }
        if (sticky) {
            fields.put("sticky", true);
        }
        ObjectNode input = args().put("site", sitePath + "/")
                .put("as", "alice")
                .put("type", "post")
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    /** The terms of a post: a category (0 for none) and tags. */
    static ObjectNode terms(long category, long... tags) {
        ObjectNode terms = args();
        if (category > 0) {
            terms.putArray("category").add(category);
        }
        if (tags.length > 0) {
            ArrayNode list = terms.putArray("post_tag");
            for (long tag : tags) {
                list.add(tag);
            }
        }
        return terms;
    }

    static void option(String sitePath, String name, TextNode value) throws Exception {
        site.operation(
                "option", args().put("site", sitePath + "/").put("name", name).set("value", value));
    }

    /** A subdirectory site at {@code /id/} with the given permalink structure (empty for plain mode). */
    static String createSite(String id, String structure) throws Exception {
        site.operation("create_site", args().put("path", "/" + id + "/").put("title", id));
        site.operation(
                "option",
                args().put("site", "/" + id + "/")
                        .put("name", "permalink_structure")
                        .set("value", TextNode.valueOf(structure)));
        return "/" + id;
    }

    static long post(
            String sitePath,
            String type,
            String status,
            String title,
            String slug,
            String date,
            long parent,
            ObjectNode terms)
            throws Exception {
        ObjectNode fields = args().put("post_name", slug).put("post_date", date);
        if (parent > 0) {
            fields.put("post_parent", parent);
        }
        if (terms != null) {
            fields.set("terms", terms);
        }
        ObjectNode input = args().put("site", sitePath + "/")
                .put("as", "alice")
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    /** A term through REST; the plain route, since a plain-permalink site has no {@code /wp-json/} rule. */
    static long term(String sitePath, String base, String json) throws Exception {
        Exchange created =
                client.send(RestCall.json("alice", "POST", sitePath + "/index.php?rest_route=/wp/v2/" + base, json));
        assertThat(created.status())
                .as("fixture %s %s: %s", base, json, created.text())
                .isEqualTo(201);
        return created.json().get("id").longValue();
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    /** Sends an anonymous GET and records what came back under {@code item}. */
    static void record(String item, String target) throws Exception {
        record(item, target, null);
    }

    /** Sends a GET in {@code session} (anonymous when null) and records what came back under {@code item}. */
    static void record(String item, String target, Session session) throws Exception {
        RestCall call = RestCall.get("anonymous", target);
        if (session != null) {
            call = call.withHeader("Cookie", session.cookie());
        }
        Exchange exchange = client.send(call);
        ArrayNode list = items.has(item) ? (ArrayNode) items.get(item) : items.putArray(item);
        ObjectNode entry = list.addObject();
        entry.put("request", target);
        if (session != null) {
            entry.put("as", session.user());
        }
        entry.put("status", exchange.status());
        for (String header : HEADERS) {
            List<String> lines = exchange.headerLines(header);
            if (lines.size() == 1) {
                entry.put(header.toLowerCase(), lines.get(0));
            } else if (!lines.isEmpty()) {
                ArrayNode values = entry.putArray(header.toLowerCase());
                lines.forEach(values::add);
            }
        }
        byte[] body = exchange.body();
        byte[] head = Arrays.copyOf(body, Math.min(body.length, BODY_BYTES));
        entry.put("body", new String(head, StandardCharsets.UTF_8));
        entry.put("bodyBytes", body.length);
        String html = new String(body, StandardCharsets.UTF_8);
        between(html, "<title>", "</title>").stream().findFirst().ifPresent(title -> entry.put("title", title));
        List<String> dates = between(html, "<time datetime=\"", "\"");
        if (!dates.isEmpty()) {
            ArrayNode times = entry.putArray("dates");
            dates.forEach(times::add);
        }
        if (html.contains("<rss")) {
            ArrayNode titles = entry.putArray("feedTitles");
            between(html, "<title>", "</title>").forEach(titles::add);
        }
        List<String> listed = between(html, "class=\"wp-block-post-title", "</h");
        if (!listed.isEmpty()) {
            ArrayNode titles = entry.putArray("listed");
            for (String heading : listed) {
                int close = heading.endsWith("</a>") ? heading.length() - 4 : heading.length();
                int open = heading.lastIndexOf('>', close - 1);
                titles.add(heading.substring(open + 1, close));
            }
        }
    }

    /** The texts between each {@code start} and the next {@code end}, in order (the theme's own markup). */
    static List<String> between(String html, String start, String end) {
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = html.indexOf(start, from);
            if (open < 0) {
                return found;
            }
            int close = html.indexOf(end, open + start.length());
            if (close < 0) {
                return found;
            }
            found.add(html.substring(open + start.length(), close));
            from = close + end.length();
        }
    }
}
