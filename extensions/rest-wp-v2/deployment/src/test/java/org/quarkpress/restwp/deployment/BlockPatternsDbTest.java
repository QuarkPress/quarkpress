package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's block pattern routes ({@code /wp/v2/block-patterns/patterns} and {@code /categories}) over the registered
 * patterns and categories, as WordPress 7.1.3 answered them: for anyone who may edit posts of some type, the patterns
 * in registration order with their nested patterns resolved and only the keys they set, old category names answered
 * by their new ones; the core categories, then the application's; any {@code context} accepted, an unknown one giving
 * one empty record per item. Members of the main site: alice (administrator), dave (contributor) and erin
 * (subscriber).
 */
class BlockPatternsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            BlockPatternsDbTest.class, TestPatterns.class, TestPatterns.Patterns.class, TestPatterns.Categories.class);

    private static final String PATTERNS = "/wp-json/wp/v2/block-patterns/patterns";

    private static final String CATEGORIES = "/wp-json/wp/v2/block-patterns/categories";

    /** WordPress's answer for the harness's three patterns, recorded (b11). */
    private static final String RECORDED_PATTERNS =
            "[{\"name\":\"qp\\/inner\",\"title\":\"Inner\",\"content\":\"<!-- wp:paragraph --><p>Inner<\\"
                    + "/p><!-- \\/wp:paragraph -->\",\"categories\":[\"call-to-action\",\"text\",\"posts\",\"x\"]},"
                    + "{\"name\":\"qp\\/outer\",\"title\":\"Outer\",\"content\":\"<!-- wp:group --><div class=\\\"w"
                    + "p-block-group\\\"><!-- wp:paragraph {\\\"metadata\\\":{\\\"patternName\\\":\\\"qp\\/inner\\\""
                    + ",\\\"name\\\":\\\"Inner\\\",\\\"categories\\\":[\\\"buttons\\\",\\\"columns\\\",\\\"query\\\""
                    + ",\\\"x\\\"]}} --><p>Inner<\\/p><!-- \\/wp:paragraph --><!-- wp:pattern {\\\"slug\\\":\\\"qp\\"
                    + "/missing\\\"} \\/--><!-- wp:group {\\\"metadata\\\":{\\\"patternName\\\":\\\"qp\\/outer\\\","
                    + "\\\"name\\\":\\\"Outer\\\"}} --><div class=\\\"wp-block-group\\\"><!-- wp:paragraph {\\\"met"
                    + "adata\\\":{\\\"patternName\\\":\\\"qp\\/inner\\\",\\\"name\\\":\\\"Inner\\\",\\\"categories\\"
                    + "\":[\\\"buttons\\\",\\\"columns\\\",\\\"query\\\",\\\"x\\\"]}} --><p>Inner<\\/p><!-- \\/wp:p"
                    + "aragraph --><!-- wp:pattern {\\\"slug\\\":\\\"qp\\/missing\\\"} \\/--><\\/div><!-- \\/wp:gro"
                    + "up --><\\/div><!-- \\/wp:group -->\"},{\"name\":\"qp\\/plain\",\"title\":\"Plain\",\"content"
                    + "\":\"<!-- wp:paragraph --><p>Plain<\\/p><!-- \\/wp:paragraph -->\",\"description\":\"Plain t"
                    + "ext.\",\"viewport_width\":500,\"inserter\":false,\"keywords\":[\"plain\"],\"block_types\":[\""
                    + "core\\/paragraph\"],\"post_types\":[\"post\"]}]";

    /** WordPress's OPTIONS of the patterns route (b14), the subsite's path left out. */
    private static final String PATTERNS_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],\"arg"
                    + "s\":[]}],\"schema\":{\"$schema\":\"http:\\/\\/json-schema.org\\/draft-04\\/schema#\",\"title"
                    + "\":\"block-pattern\",\"type\":\"object\",\"properties\":{\"name\":{\"description\":\"The pat"
                    + "tern name.\",\"type\":\"string\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]"
                    + "},\"title\":{\"description\":\"The pattern title, in human readable format.\",\"type\":\"str"
                    + "ing\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"content\":{\"descriptio"
                    + "n\":\"The pattern content.\",\"type\":\"string\",\"readonly\":true,\"context\":[\"view\",\"e"
                    + "dit\",\"embed\"]},\"description\":{\"description\":\"The pattern detailed description.\",\"t"
                    + "ype\":\"string\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"viewport_wid"
                    + "th\":{\"description\":\"The pattern viewport width for inserter preview.\",\"type\":\"number"
                    + "\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"inserter\":{\"description\""
                    + ":\"Determines whether the pattern is visible in inserter.\",\"type\":\"boolean\",\"readonly\""
                    + ":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"categories\":{\"description\":\"The patte"
                    + "rn category slugs.\",\"type\":\"array\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"e"
                    + "mbed\"]},\"keywords\":{\"description\":\"The pattern keywords.\",\"type\":\"array\",\"readon"
                    + "ly\":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"block_types\":{\"description\":\"Bloc"
                    + "k types that the pattern is intended to be used with.\",\"type\":\"array\",\"readonly\":true"
                    + ",\"context\":[\"view\",\"edit\",\"embed\"]},\"post_types\":{\"description\":\"An array of po"
                    + "st types that the pattern is restricted to be used with.\",\"type\":\"array\",\"readonly\":t"
                    + "rue,\"context\":[\"view\",\"edit\",\"embed\"]},\"template_types\":{\"description\":\"An arra"
                    + "y of template types where the pattern fits.\",\"type\":\"array\",\"readonly\":true,\"context"
                    + "\":[\"view\",\"edit\",\"embed\"]},\"source\":{\"description\":\"Where the pattern comes from"
                    + " e.g. core\",\"type\":\"string\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]"
                    + ",\"enum\":[\"core\",\"plugin\",\"theme\",\"pattern-directory\\/core\",\"pattern-directory\\/"
                    + "theme\",\"pattern-directory\\/featured\"]}}},\"_links\":{\"self\":[{\"href\":\"http:\\/\\/lo"
                    + "calhost\\/wp-json\\/wp\\/v2\\/block-patterns\\/patterns\"}]}}";

    /** WordPress's OPTIONS of the categories route (b14), the subsite's path left out. */
    private static final String CATEGORIES_OPTIONS =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],\"arg"
                    + "s\":[]}],\"schema\":{\"$schema\":\"http:\\/\\/json-schema.org\\/draft-04\\/schema#\",\"title"
                    + "\":\"block-pattern-category\",\"type\":\"object\",\"properties\":{\"name\":{\"description\":"
                    + "\"The category name.\",\"type\":\"string\",\"readonly\":true,\"context\":[\"view\",\"edit\","
                    + "\"embed\"]},\"label\":{\"description\":\"The category label, in human readable format.\",\"t"
                    + "ype\":\"string\",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]},\"description\""
                    + ":{\"description\":\"The category description, in human readable format.\",\"type\":\"string\""
                    + ",\"readonly\":true,\"context\":[\"view\",\"edit\",\"embed\"]}}},\"_links\":{\"self\":[{\"hre"
                    + "f\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/block-patterns\\/categories\"}]}}";

    private static final List<String> CORE_CATEGORIES = List.of(
            "banner",
            "buttons",
            "columns",
            "text",
            "query",
            "featured",
            "call-to-action",
            "team",
            "testimonials",
            "services",
            "contact",
            "about",
            "portfolio",
            "gallery",
            "media",
            "videos",
            "audio",
            "posts",
            "footer",
            "header",
            "navigation");

    static long alice;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        AgroalDataSource data = Jdbc.dataSource();
        alice = TestData.memberOfMain(data, "administrator").value();
        dave = TestData.memberOfMain(data, "contributor").value();
        erin = TestData.memberOfMain(data, "subscriber").value();
    }

    static RequestSpecification as(long user) {
        return Http.as(user == 0 ? null : "test-" + user);
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void patternsInOrderWithTheirKeys() {
        Response patterns = as(dave).get(PATTERNS);
        patterns.then().statusCode(200).header("Allow", "GET");
        assertThat(patterns.getHeader("X-WP-Total")).isNull();
        // byte for byte: qp/inner's categories migrated in place, qp/outer's references resolved (its self reference
        // once), qp/plain with every other key and no categories or source
        assertThat(patterns.asString()).isEqualTo(RECORDED_PATTERNS);
        // the edit and embed contexts hold every field too
        as(dave).get(PATTERNS + "?context=edit").then().body(equalTo(RECORDED_PATTERNS));
        as(dave).get(PATTERNS + "?context=embed").then().body(equalTo(RECORDED_PATTERNS));
    }

    @Test
    void permission() {
        String patterns = "{\"code\":\"rest_cannot_view\",\"message\":\"Sorry, you are not allowed to view the"
                + " registered block patterns.\",\"data\":{\"status\":%d}}";
        String categories = "{\"code\":\"rest_cannot_view\",\"message\":\"Sorry, you are not allowed to view the"
                + " registered block pattern categories.\",\"data\":{\"status\":%d}}";
        as(erin).get(PATTERNS)
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo(patterns.formatted(403)));
        as(0).get(PATTERNS).then().statusCode(401).body(equalTo(patterns.formatted(401)));
        as(erin).get(CATEGORIES).then().statusCode(403).body(equalTo(categories.formatted(403)));
        as(0).get(CATEGORIES).then().statusCode(401).body(equalTo(categories.formatted(401)));
        as(dave).get(CATEGORIES).then().statusCode(200).header("Allow", "GET");
        as(alice).get(PATTERNS).then().statusCode(200).header("Allow", "GET");
    }

    @Test
    void contextQuirkAndFields() {
        // no context argument is declared, so any value is accepted, and no field is in an unknown one
        as(dave).get(PATTERNS + "?context=foo").then().statusCode(200).body(equalTo("[[],[],[]]"));
        as(dave).get(CATEGORIES + "?context=foo")
                .then()
                .statusCode(200)
                .body(equalTo("[" + "[],".repeat(CORE_CATEGORIES.size()) + "[]]"));
        as(dave).get(PATTERNS + "?_fields=name")
                .then()
                .body(equalTo("[{\"name\":\"qp\\/inner\"},{\"name\":\"qp\\/outer\"},{\"name\":\"qp\\/plain\"}]"));
        as(dave).get(PATTERNS + "?_fields=description,inserter")
                .then()
                .body(equalTo("[[],[],{\"description\":\"Plain text.\",\"inserter\":false}]"));
        as(dave).head(PATTERNS).then().statusCode(200).header("Allow", "GET").body(equalTo(""));
        as(dave).head(CATEGORIES).then().statusCode(200).header("Allow", "GET").body(equalTo(""));
    }

    @Test
    void categoriesAreTheCoreListThenTheApps() {
        Response categories = as(alice).get(CATEGORIES);
        categories.then().statusCode(200).header("Allow", "GET");
        JsonNode list = json(categories.asString());
        List<String> names = new ArrayList<>();
        list.forEach(category -> names.add(category.get("name").asText()));
        List<String> expected = new ArrayList<>(CORE_CATEGORIES);
        expected.add("acme");
        assertThat(names).isEqualTo(expected);
        // WordPress's labels (two of them "Posts"), QuarkPress's descriptions
        assertThat(list.get(0).toString())
                .isEqualTo("{\"name\":\"banner\",\"label\":\"Banners\","
                        + "\"description\":\"Large sections that open a page or carry one message.\"}");
        assertThat(list.get(4).get("label").asText()).isEqualTo("Posts");
        assertThat(list.get(17).get("label").asText()).isEqualTo("Posts");
        assertThat(list.get(19).get("label").asText()).isEqualTo("Headers");
        // the application's category: only the keys it sets
        assertThat(list.get(21).toString()).isEqualTo("{\"name\":\"acme\",\"label\":\"Acme\"}");
        as(alice)
                .get(CATEGORIES + "?_fields=label")
                .then()
                .body(startsWith("[{\"label\":\"Banners\"},{\"label\":\"Buttons\"},"));
    }

    @Test
    void optionsAsWordPress() {
        as(alice)
                .options(PATTERNS)
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo(PATTERNS_OPTIONS));
        as(alice)
                .options(CATEGORIES)
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo(CATEGORIES_OPTIONS));
    }
}
