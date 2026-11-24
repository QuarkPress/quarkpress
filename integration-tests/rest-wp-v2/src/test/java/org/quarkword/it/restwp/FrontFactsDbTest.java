package org.quarkword.it.restwp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * (FQ-G1 to FQ-G9, FQ-G14, FQ-G15, FQ-G22, FQ-G23), into {@code target/wp-rest-parity/front-facts.json}: status,
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

    // ---- helpers --------------------------------------------------------------------------------------------------

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

    static long term(String sitePath, String base, String json) throws Exception {
        Exchange created = client.send(RestCall.json("alice", "POST", sitePath + "/wp-json/wp/v2/" + base, json));
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
        Exchange exchange = client.send(RestCall.get("anonymous", target));
        ArrayNode list = items.has(item) ? (ArrayNode) items.get(item) : items.putArray(item);
        ObjectNode entry = list.addObject();
        entry.put("request", target);
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
        List<String> listed = between(html, "class=\"wp-block-post-title", "</h2>");
        if (!listed.isEmpty()) {
            ArrayNode titles = entry.putArray("listed");
            for (String heading : listed) {
                int open = heading.lastIndexOf('>');
                int close = heading.lastIndexOf("</a>");
                titles.add(close > open ? heading.substring(open + 1, close) : heading);
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
