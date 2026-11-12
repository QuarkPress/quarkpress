package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The themes routes ({@code /wp/v2/themes}, {@code /wp/v2/themes/<stylesheet>}) serving the synthetic theme
 * {@code quarkpress} as WordPress serves its active theme (Part G facts v01-v07): the record's fields in WordPress's
 * order with the recorded header values, the 24 theme supports with their defaults, the site's own directory URL, the
 * 26 template types and 4 template part areas; WordPress's permission rule (the theme capabilities, else exactly
 * {@code status=active} or the active theme's own item for a caller who may edit posts of some type); validation
 * before permission; the item lookup after permission. Members of a site of its own: alice (administrator and super
 * admin), the editor bob, the author carol, the contributor dave and the subscriber erin.
 */
class ThemesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ThemesDbTest.class);

    /** The recorded record up to {@code status} (Part G fact v07), {@code HOST} standing for the site's host. */
    private static final String HEADER_FIELDS = "{\"stylesheet\":\"quarkpress\",\"template\":\"quarkpress\","
            + "\"requires_php\":\"\",\"requires_wp\":\"\",\"textdomain\":\"\",\"version\":\"\",\"screenshot\":\"\","
            + "\"author\":{\"raw\":\"\",\"rendered\":\"Anonymous\"},\"author_uri\":{\"raw\":\"\",\"rendered\":\"\"},"
            + "\"description\":{\"raw\":\"The synthetic theme QuarkPress serves to the block editor.\","
            + "\"rendered\":\"The synthetic theme QuarkPress serves to the block editor.\"},"
            + "\"name\":{\"raw\":\"QuarkPress\",\"rendered\":\"QuarkPress\"},\"tags\":{\"raw\":[],\"rendered\":\"\"},"
            + "\"theme_uri\":{\"raw\":\"\",\"rendered\":\"\"},\"status\":\"active\"";

    /** The 24 supports in WordPress's order with the defaults of the synthetic theme (fact v07). */
    static final String DEFAULT_SUPPORTS = "{\"align-wide\":true,\"automatic-feed-links\":false,"
            + "\"block-templates\":false,\"block-template-parts\":false,\"custom-background\":false,"
            + "\"custom-header\":false,\"custom-logo\":false,\"customize-selective-refresh-widgets\":false,"
            + "\"dark-editor-style\":false,\"disable-custom-colors\":false,\"disable-custom-font-sizes\":false,"
            + "\"disable-custom-gradients\":false,\"disable-layout-styles\":false,\"editor-color-palette\":false,"
            + "\"editor-font-sizes\":false,\"editor-gradient-presets\":false,\"editor-spacing-sizes\":false,"
            + "\"editor-styles\":true,\"html5\":[\"comment-form\",\"comment-list\",\"search-form\",\"gallery\","
            + "\"caption\",\"style\",\"script\"],\"formats\":[\"standard\"],\"post-thumbnails\":true,"
            + "\"responsive-embeds\":true,\"title-tag\":false,\"wp-block-styles\":false}";

    private static final String AREAS = "[{\"area\":\"uncategorized\",\"label\":\"General\",\"description\":\"General"
            + " templates often perform a specific role like displaying post content, and are not tied to any"
            + " particular area.\",\"icon\":\"layout\",\"area_tag\":\"div\"},{\"area\":\"header\",\"label\":\"Header\","
            + "\"description\":\"The Header template defines a page area that typically contains a title, logo, and"
            + " main navigation.\",\"icon\":\"header\",\"area_tag\":\"header\"},{\"area\":\"footer\","
            + "\"label\":\"Footer\",\"description\":\"The Footer template defines a page area that typically contains"
            + " site credits, social links, or any other combination of blocks.\",\"icon\":\"footer\","
            + "\"area_tag\":\"footer\"},{\"area\":\"navigation-overlay\",\"label\":\"Navigation Overlay\","
            + "\"description\":\"The Navigation Overlay template defines an overlay area that typically contains"
            + " navigation links and can be toggled open and closed.\",\"icon\":\"navigation-overlay\","
            + "\"area_tag\":\"div\"}]";

    private static final List<String> TEMPLATE_SLUGS = List.of(
            "index",
            "home",
            "front-page",
            "singular",
            "single",
            "page",
            "archive",
            "author",
            "category",
            "taxonomy",
            "date",
            "tag",
            "attachment",
            "search",
            "privacy-policy",
            "404",
            "taxonomy-post_format-post-format-standard",
            "taxonomy-post_format-post-format-aside",
            "taxonomy-post_format-post-format-chat",
            "taxonomy-post_format-post-format-gallery",
            "taxonomy-post_format-post-format-link",
            "taxonomy-post_format-post-format-image",
            "taxonomy-post_format-post-format-quote",
            "taxonomy-post_format-post-format-status",
            "taxonomy-post_format-post-format-video",
            "taxonomy-post_format-post-format-audio");

    private static final String CANNOT_VIEW_THEMES = "{\"code\":\"rest_cannot_view_themes\",\"message\":\"Sorry, you"
            + " are not allowed to view themes.\",\"data\":{\"status\":403}}";

    private static final String STATUS_FOO = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s):"
            + " status\",\"data\":{\"status\":400,\"params\":{\"status\":\"status[0] is not one of active and"
            + " inactive.\"},\"details\":{\"status\":{\"code\":\"rest_not_in_enum\",\"message\":\"status[0] is not"
            + " one of active and inactive.\",\"data\":null}}}}";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
        erin = member("subscriber");
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    /** A request to the site as the user, or anonymous for 0. */
    static RequestSpecification as(long user) {
        return Http.at(host, user == 0 ? null : "test-" + user);
    }

    @Test
    void theActiveThemeForEditors() {
        for (long user : List.of(bob, carol, dave)) {
            Response plain = as(user).get("/wp-json/wp/v2/themes?status=active");
            plain.then()
                    .statusCode(200)
                    .header("Allow", "GET")
                    .header("X-WP-Total", "1")
                    .header("X-WP-TotalPages", "1");
            // no context: the body is the same whatever context is asked for (the editor asks for edit)
            Response edit = as(user).get("/wp-json/wp/v2/themes?context=edit&status=active");
            edit.then().statusCode(200).header("Allow", "GET").header("X-WP-Total", "1");
            assertThat(edit.asString()).isEqualTo(plain.asString());
            // no pagination links (the API root's Link is every answer's)
            for (String link : plain.getHeaders().getValues("Link")) {
                assertThat(link).doesNotContain("rel=\"next\"").doesNotContain("rel=\"prev\"");
            }
            JsonNode list = json(plain.asString());
            assertThat(list.isArray()).isTrue();
            assertThat(list.size()).isEqualTo(1);
            theRecord(list.get(0));
        }
    }

    /** The synthetic theme's record as WordPress answers it for the harness theme (facts v01, v04, v07). */
    private void theRecord(JsonNode theme) {
        assertThat(fieldNames(theme))
                .containsExactly(
                        "stylesheet",
                        "template",
                        "requires_php",
                        "requires_wp",
                        "textdomain",
                        "version",
                        "screenshot",
                        "author",
                        "author_uri",
                        "description",
                        "name",
                        "tags",
                        "theme_uri",
                        "status",
                        "theme_supports",
                        "is_block_theme",
                        "stylesheet_uri",
                        "template_uri",
                        "default_template_types",
                        "default_template_part_areas",
                        "_links");
        ObjectNode head = ((ObjectNode) theme).deepCopy();
        head.remove(List.of(
                "theme_supports",
                "is_block_theme",
                "stylesheet_uri",
                "template_uri",
                "default_template_types",
                "default_template_part_areas",
                "_links"));
        assertThat(head.toString()).isEqualTo(HEADER_FIELDS + "}");
        assertThat(theme.get("theme_supports").toString()).isEqualTo(DEFAULT_SUPPORTS);
        assertThat(theme.get("is_block_theme").toString()).isEqualTo("false");
        // the current site's own URL, not the network's (fact v04 on a subsite)
        assertThat(theme.get("stylesheet_uri").asText()).isEqualTo("http://" + host + "/wp-content/themes/quarkpress");
        assertThat(theme.get("template_uri").asText()).isEqualTo("http://" + host + "/wp-content/themes/quarkpress");
        List<String> slugs = new ArrayList<>();
        for (JsonNode type : theme.get("default_template_types")) {
            assertThat(fieldNames(type)).containsExactly("title", "description", "slug");
            slugs.add(type.get("slug").asText());
        }
        assertThat(slugs).isEqualTo(TEMPLATE_SLUGS);
        assertThat(theme.at("/default_template_types/0").toString())
                .isEqualTo("{\"title\":\"Index\",\"description\":\"Used as a fallback template for all pages when a"
                        + " more specific template is not defined.\",\"slug\":\"index\"}");
        assertThat(theme.at("/default_template_types/16").toString())
                .isEqualTo("{\"title\":\"Post Format: Standard\",\"description\":\"Displays the Standard post format"
                        + " archive.\",\"slug\":\"taxonomy-post_format-post-format-standard\"}");
        assertThat(theme.at("/default_template_types/25/title").asText()).isEqualTo("Post Format: Audio");
        assertThat(theme.get("default_template_part_areas").toString()).isEqualTo(AREAS);
        assertThat(theme.get("_links").toString())
                .isEqualTo("{\"self\":[{\"href\":\"http://" + host + "/wp-json/wp/v2/themes/quarkpress\","
                        + "\"targetHints\":{\"allow\":[\"GET\"]}}],\"collection\":[{\"href\":\"http://" + host
                        + "/wp-json/wp/v2/themes\"}]}");
    }

    @Test
    void permissionTable() {
        // bob may edit posts but not switch themes: only exactly status=active is his (fact v02)
        for (String query : List.of("", "?status=inactive", "?status=active,inactive")) {
            Response refused = as(bob).get("/wp-json/wp/v2/themes" + query);
            refused.then().statusCode(403).body(equalTo(CANNOT_VIEW_THEMES));
            assertThat(refused.getHeaders().getValues("Allow")).isEmpty();
        }
        as(bob).get("/wp-json/wp/v2/themes?status[]=active")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "1");
        // validation before permission: an unknown status is 400 for anyone, without Allow (fact v02)
        for (long user : List.of(bob, 0L)) {
            Response invalid = as(user).get("/wp-json/wp/v2/themes?status=foo");
            invalid.then().statusCode(400).body(equalTo(STATUS_FOO));
            assertThat(invalid.getHeaders().getValues("Allow")).isEmpty();
        }
        // a subscriber and an anonymous caller may not see even the active theme (fact v07)
        Response erins = as(erin).get("/wp-json/wp/v2/themes?status=active");
        erins.then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_cannot_view_active_theme\",\"message\":\"Sorry, you are not allowed"
                        + " to view the active theme.\",\"data\":{\"status\":403}}"));
        assertThat(erins.getHeaders().getValues("Allow")).isEmpty();
        as(0).get("/wp-json/wp/v2/themes?context=edit&status=active")
                .then()
                .statusCode(401)
                .body(equalTo("{\"code\":\"rest_cannot_view_active_theme\",\"message\":\"Sorry, you are not allowed"
                        + " to view the active theme.\",\"data\":{\"status\":401}}"));
        // alice switches themes: every list is hers, and the only theme there is is the active one
        Response all = as(alice).get("/wp-json/wp/v2/themes");
        all.then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "1")
                .header("X-WP-TotalPages", "1");
        assertThat(json(all.asString()).size()).isEqualTo(1);
        assertThat(json(all.asString()).at("/0/stylesheet").asText()).isEqualTo("quarkpress");
        // HEAD: the headers without a body
        as(dave).head("/wp-json/wp/v2/themes?status=active")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "1")
                .body(equalTo(""));
    }

    @Test
    void items() {
        Response own = as(bob).get("/wp-json/wp/v2/themes/quarkpress");
        own.then().statusCode(200).header("Allow", "GET");
        theRecord(json(own.asString()));
        assertThat(own.getHeaders().getValues("X-WP-Total")).isEmpty();
        // the item is matched against the active theme before anything is looked up (fact v04: bob's nope is 403)
        Response nope = as(bob).get("/wp-json/wp/v2/themes/nope");
        nope.then().statusCode(403).body(equalTo(CANNOT_VIEW_THEMES));
        assertThat(nope.getHeaders().getValues("Allow")).isEmpty();
        as(erin).get("/wp-json/wp/v2/themes/quarkpress")
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_cannot_view_active_theme\",\"message\":\"Sorry, you are not allowed"
                        + " to view the active theme.\",\"data\":{\"status\":403}}"));
        as(0).get("/wp-json/wp/v2/themes/quarkpress")
                .then()
                .statusCode(401)
                .body(equalTo("{\"code\":\"rest_cannot_view_active_theme\",\"message\":\"Sorry, you are not allowed"
                        + " to view the active theme.\",\"data\":{\"status\":401}}"));
        // alice passes the permission, so an unknown theme is the lookup's 404, with Allow (fact v04)
        for (String stylesheet : List.of("nope", "a/b", "twentytwentyfive", "Quarkpress")) {
            as(alice)
                    .get("/wp-json/wp/v2/themes/" + stylesheet)
                    .then()
                    .statusCode(404)
                    .header("Allow", "GET")
                    .body(equalTo("{\"code\":\"rest_theme_not_found\",\"message\":\"Theme not found.\","
                            + "\"data\":{\"status\":404}}"));
        }
        // the stylesheet is URL-decoded exactly once, with the request path (sent raw: REST Assured would otherwise
        // encode the % itself): a doubly encoded name stays quarkpres%73 and is not a theme, as WordPress answers
        // (parity scenario f06)
        as(alice)
                .urlEncodingEnabled(false)
                .get("/wp-json/wp/v2/themes/quarkpres%2573")
                .then()
                .statusCode(404)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_theme_not_found\",\"message\":\"Theme not found.\","
                        + "\"data\":{\"status\":404}}"));
        as(alice)
                .urlEncodingEnabled(false)
                .get("/wp-json/wp/v2/themes/quarkpres%73")
                .then()
                .statusCode(200)
                .body("stylesheet", equalTo("quarkpress"));
        // three segments are no route
        as(alice).get("/wp-json/wp/v2/themes/a/b/c").then().statusCode(404).body("code", equalTo("rest_no_route"));
        // only the active theme: no inactive one is ever listed
        as(alice)
                .get("/wp-json/wp/v2/themes?status=inactive")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "1")
                .body(equalTo("[]"));
    }

    @Test
    void noGlobalStylesLink() {
        // WordPress links the theme's user global styles (creating the post) and, for a block theme, the export;
        // the synthetic theme is a classic theme without global styles, so neither link is given, and so no curie
        JsonNode theme = json(as(bob).get("/wp-json/wp/v2/themes/quarkpress").asString());
        assertThat(fieldNames(theme.get("_links"))).containsExactly("self", "collection");
        JsonNode listed =
                json(as(bob).get("/wp-json/wp/v2/themes?status=active").asString());
        assertThat(fieldNames(listed.at("/0/_links"))).containsExactly("self", "collection");
    }

    @Test
    void fieldsReachNestedSupports() {
        // fact v05: a nested field keeps only that key of the support object
        as(bob).get("/wp-json/wp/v2/themes?status=active&_fields=theme_supports.formats,stylesheet")
                .then()
                .statusCode(200)
                .body(equalTo("[{\"stylesheet\":\"quarkpress\",\"theme_supports\":{\"formats\":[\"standard\"]}}]"));
        as(alice)
                .get("/wp-json/wp/v2/themes/quarkpress?_fields=name,is_block_theme")
                .then()
                .statusCode(200)
                .body("name.rendered", equalTo("QuarkPress"))
                .body("is_block_theme", equalTo(false));
    }

    @Test
    void optionsDescribeTheRoutesAsWordPress() {
        // fact v06: one argument per route, and the 24 supports in the schema with WordPress's types
        JsonNode collection = json(as(alice).options("/wp-json/wp/v2/themes").asString());
        assertThat(collection.get("methods").toString()).isEqualTo("[\"GET\"]");
        assertThat(collection.at("/endpoints/0/args").toString())
                .isEqualTo("{\"status\":{\"description\":\"Limit result set to themes assigned one or more"
                        + " statuses.\",\"type\":\"array\",\"items\":{\"enum\":[\"active\",\"inactive\"],"
                        + "\"type\":\"string\"},\"required\":false}}");
        assertThat(collection.at("/schema/title").asText()).isEqualTo("theme");
        assertThat(fieldNames(collection.at("/schema/properties/theme_supports/properties")))
                .containsExactly(fieldNames(json(DEFAULT_SUPPORTS)).toArray(new String[0]));
        assertThat(collection
                        .at("/schema/properties/theme_supports/properties/html5/type")
                        .toString())
                .isEqualTo("[\"boolean\",\"array\"]");
        // the quirk WordPress prints: the formats enum as an object
        assertThat(collection
                        .at("/schema/properties/theme_supports/properties/formats/items/enum")
                        .toString())
                .isEqualTo("{\"standard\":\"standard\",\"aside\":\"aside\",\"chat\":\"chat\",\"gallery\":\"gallery\","
                        + "\"link\":\"link\",\"image\":\"image\",\"quote\":\"quote\",\"status\":\"status\","
                        + "\"video\":\"video\",\"audio\":\"audio\"}");
        JsonNode item =
                json(as(alice).options("/wp-json/wp/v2/themes/quarkpress").asString());
        assertThat(item.at("/endpoints/0/args").toString())
                .isEqualTo("{\"stylesheet\":{\"description\":\"The theme's stylesheet. This uniquely identifies the"
                        + " theme.\",\"type\":\"string\",\"required\":false}}");
        assertThat(item.get("schema")).isEqualTo(collection.get("schema"));
        // no POST, no batching
        as(alice).post("/wp-json/wp/v2/themes").then().statusCode(404).body("code", equalTo("rest_no_route"));
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
