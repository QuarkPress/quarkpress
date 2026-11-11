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
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's block types routes ({@code /wp/v2/block-types}, by namespace, and one type) over the registered block
 * types, as WordPress 7.1.3 answered them: for anyone who may edit posts of some type, every field in WordPress's
 * order with its defaults (empty objects printed {@code []}), the attributes block supports add, links to the
 * collection, the namespace and the renderer of a dynamic type, the query's namespace over the URL's, an unknown
 * type refused by the permission check (so without {@code Allow}). Members of the main site: alice (administrator),
 * bob (editor), dave (contributor) and erin (subscriber).
 */
class BlockTypesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(BlockTypesDbTest.class, TestBlocks.class, TestBlocks.Renderer.class);

    private static final String ROUTE = "/wp-json/wp/v2/block-types";

    /** WordPress's acme/notice (registered with a title only), with the subsite's path left out of its links. */
    private static final String NOTICE =
            "{\"attributes\":{\"lock\":{\"type\":\"object\"},\"metadata\":{\"type\":\"object\"},"
                    + "\"className\":{\"type\":\"string\"},\"style\":{\"type\":\"object\"}},\"is_dynamic\":false,"
                    + "\"api_version\":1,\"name\":\"acme\\/notice\",\"title\":\"Notice\",\"description\":\"\",\"icon\":null,"
                    + "\"category\":null,\"keywords\":[],\"parent\":null,\"ancestor\":null,\"allowed_blocks\":null,"
                    + "\"provides_context\":[],\"uses_context\":[],\"selectors\":[],\"supports\":[],\"styles\":[],"
                    + "\"textdomain\":null,\"example\":null,\"editor_script_handles\":[],\"script_handles\":[],"
                    + "\"view_script_handles\":[],\"view_script_module_ids\":[],\"editor_style_handles\":[],"
                    + "\"style_handles\":[],\"view_style_handles\":[],\"variations\":[],\"block_hooks\":[],"
                    + "\"editor_script\":null,\"script\":null,\"view_script\":null,\"editor_style\":null,\"style\":null,"
                    + "\"_links\":{\"collection\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/block-types\"}],"
                    + "\"self\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/block-types\\/acme\\/notice\","
                    + "\"targetHints\":{\"allow\":[\"GET\"]}}],"
                    + "\"up\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/block-types\\/acme\"}]}}";

    private static final String INVALID =
            "{\"code\":\"rest_block_type_invalid\",\"message\":\"Invalid block type.\",\"data\":{\"status\":404}}";

    private static final String NO_ROUTE = "{\"code\":\"rest_no_route\","
            + "\"message\":\"No route was found matching the URL and request method.\",\"data\":{\"status\":404}}";

    /** WordPress's answer to {@code context=foo} (b12). */
    private static final String CONTEXT_FOO = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s):"
            + " context\",\"data\":{\"status\":400,\"params\":{\"context\":\"context is not one of view, embed, and"
            + " edit.\"},\"details\":{\"context\":{\"code\":\"rest_not_in_enum\",\"message\":\"context is"
            + " not one of view, embed, and edit.\",\"data\":null}}}}";

    static long alice;
    static long bob;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        AgroalDataSource data = Jdbc.dataSource();
        alice = TestData.memberOfMain(data, "administrator").value();
        bob = TestData.memberOfMain(data, "editor").value();
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

    static List<String> names(String body) {
        List<String> names = new ArrayList<>();
        json(body).forEach(type -> names.add(type.get("name").asText()));
        return names;
    }

    @Test
    void theDefaultsByteForByte() {
        Response notice = as(dave).get(ROUTE + "/acme/notice");
        notice.then().statusCode(200).header("Allow", "GET");
        assertThat(notice.asString()).isEqualTo(NOTICE);
    }

    @Test
    void everyPropertyInWordPresssOrder() {
        ObjectNode full = (ObjectNode) json(as(bob).get(ROUTE + "/acme/full").asString());
        full.remove("_links");
        // the declared empty object default prints as WordPress's empty array; the empty style is left out; the
        // deprecated singular fields name the first handle; no support before customCSS wants style, so it is last
        assertThat(full.toString())
                .isEqualTo("{\"attributes\":{\"content\":{\"type\":\"string\",\"default\":\"\"},"
                        + "\"opts\":{\"type\":\"object\",\"default\":[]},\"lock\":{\"type\":\"object\"},"
                        + "\"metadata\":{\"type\":\"object\"},\"className\":{\"type\":\"string\"},"
                        + "\"anchor\":{\"type\":\"string\"},\"style\":{\"type\":\"object\"}},"
                        + "\"is_dynamic\":false,\"api_version\":3,\"name\":\"acme/full\",\"title\":\"Full\","
                        + "\"description\":\"Every property.\",\"icon\":\"smiley\",\"category\":\"widgets\","
                        + "\"keywords\":[\"a\",\"b\"],\"parent\":[\"core/group\"],\"ancestor\":[\"core/columns\"],"
                        + "\"allowed_blocks\":[\"core/paragraph\"],\"provides_context\":{\"acme/id\":\"content\"},"
                        + "\"uses_context\":[\"postId\"],\"selectors\":{\"root\":\".acme\"},"
                        + "\"supports\":{\"anchor\":true,\"html\":false},"
                        + "\"styles\":[{\"name\":\"plain\",\"label\":\"Plain\",\"is_default\":true}],"
                        + "\"textdomain\":\"acme\",\"example\":{\"attributes\":{\"content\":\"x\"}},"
                        + "\"editor_script_handles\":[\"acme-editor\",\"acme-editor-2\"],\"script_handles\":[\"acme\"],"
                        + "\"view_script_handles\":[],\"view_script_module_ids\":[\"@acme/view\"],"
                        + "\"editor_style_handles\":[\"acme-editor-style\"],\"style_handles\":[\"acme-style\"],"
                        + "\"view_style_handles\":[\"acme-view\"],\"variations\":[{\"name\":\"v\",\"title\":\"V\"}],"
                        + "\"block_hooks\":{\"core/paragraph\":\"after\"},\"editor_script\":\"acme-editor\","
                        + "\"script\":\"acme\",\"view_script\":null,\"editor_style\":\"acme-editor-style\","
                        + "\"style\":\"acme-style\"}");
        // _fields limits the fields computed
        assertThat(as(bob).get(ROUTE + "/acme/full?_fields=name,is_dynamic").asString())
                .isEqualTo("{\"is_dynamic\":false,\"name\":\"acme\\/full\"}");
    }

    @Test
    void dynamicTypesLinkTheRenderer() {
        JsonNode dyn = json(as(alice).get(ROUTE + "/acme/dyn").asString());
        assertThat(dyn.get("is_dynamic").booleanValue()).isTrue();
        assertThat(dyn.get("_links").toString())
                .isEqualTo("{\"collection\":[{\"href\":\"http://localhost/wp-json/wp/v2/block-types\"}],"
                        + "\"self\":[{\"href\":\"http://localhost/wp-json/wp/v2/block-types/acme/dyn\","
                        + "\"targetHints\":{\"allow\":[\"GET\"]}}],"
                        + "\"up\":[{\"href\":\"http://localhost/wp-json/wp/v2/block-types/acme\"}],"
                        + "\"wp:render-block\":[{\"href\":"
                        + "\"http://localhost/wp-json/wp/v2/block-renderer/acme/dyn?context=edit\"}],"
                        + "\"curies\":[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]}");
        // links are computed only when asked for
        assertThat(json(as(alice).get(ROUTE + "/acme/dyn?_fields=name").asString())
                        .has("_links"))
                .isFalse();
    }

    @Test
    void permissionAndUnknownItems() {
        as(erin).get(ROUTE + "/acme/notice")
                .then()
                .statusCode(403)
                .body(
                        equalTo(
                                "{\"code\":\"rest_block_type_cannot_view\","
                                        + "\"message\":\"Sorry, you are not allowed to manage block types.\",\"data\":{\"status\":403}}"));
        as(erin).get(ROUTE).then().statusCode(403);
        as(erin).get(ROUTE + "/acme").then().statusCode(403);
        for (String target : List.of(ROUTE, ROUTE + "/acme", ROUTE + "/acme/notice", ROUTE + "/acme/nope")) {
            as(0).get(target)
                    .then()
                    .statusCode(401)
                    .body(equalTo("{\"code\":\"rest_block_type_cannot_view\","
                            + "\"message\":\"Sorry, you are not allowed to manage block types.\","
                            + "\"data\":{\"status\":401}}"));
        }
        Response nope = as(alice).get(ROUTE + "/acme/nope");
        nope.then()
                .statusCode(404)
                .body(equalTo("{\"code\":\"rest_block_type_invalid\",\"message\":\"Invalid block type.\","
                        + "\"data\":{\"status\":404}}"));
        // refused by the permission check, so without Allow
        assertThat(nope.getHeader("Allow")).isNull();
        // a contributor edits posts
        as(dave).get(ROUTE).then().statusCode(200).header("Allow", "GET");
    }

    @Test
    void editingPostsOfAnyRestTypeIsEnough() {
        // a member without edit_posts who may edit pages (a REST type), and one who may only read
        long pages = memberWithCapabilities("{\"read\":true,\"edit_pages\":true}");
        long reader = memberWithCapabilities("{\"read\":true}");
        as(pages).get(ROUTE + "/acme/notice").then().statusCode(200);
        as(pages).get(ROUTE).then().statusCode(200).header("Allow", "GET");
        as(reader)
                .get(ROUTE)
                .then()
                .statusCode(403)
                .body(
                        equalTo(
                                "{\"code\":\"rest_block_type_cannot_view\","
                                        + "\"message\":\"Sorry, you are not allowed to manage block types.\",\"data\":{\"status\":403}}"));
    }

    /** A member of the main site with no role and these individual capabilities (a JSON object). */
    private static long memberWithCapabilities(String capabilities) {
        AgroalDataSource data = Jdbc.dataSource();
        long user = TestData.profile(data, "caps").value();
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                TestData.MAIN.value(),
                user,
                "[]",
                capabilities);
        return user;
    }

    @Test
    void namesAreCaseSensitive() {
        for (String target : List.of("/acme/Notice", "/Acme/notice")) {
            as(alice).get(ROUTE + target).then().statusCode(404).body(equalTo(INVALID));
        }
        for (String target : List.of("/acme/a/b", "/acme.x")) {
            as(alice).get(ROUTE + target).then().statusCode(404).body(equalTo(NO_ROUTE));
        }
        // the route matches ignoring case; the namespace filter does not
        assertThat(as(alice).get(ROUTE + "/ACME").asString()).isEqualTo("[]");
    }

    @Test
    void namespaceFilters() {
        assertThat(names(as(alice).get(ROUTE).asString()))
                .containsExactly("acme/notice", "acme/dyn", "acme/full", "other/plain");
        assertThat(names(as(alice).get(ROUTE + "?namespace=").asString()))
                .containsExactly("acme/notice", "acme/dyn", "acme/full", "other/plain");
        Response acme = as(alice).get(ROUTE + "/acme");
        acme.then().statusCode(200).header("Allow", "GET");
        assertThat(acme.getHeader("X-WP-Total")).isNull();
        assertThat(names(acme.asString())).containsExactly("acme/notice", "acme/dyn", "acme/full");
        assertThat(names(as(alice).get(ROUTE + "?namespace=acme").asString()))
                .containsExactly("acme/notice", "acme/dyn", "acme/full");
        // the query parameter wins over the URL segment
        assertThat(as(alice).get(ROUTE + "/acme?namespace=zeta").asString()).isEqualTo("[]");
        assertThat(names(as(alice).get(ROUTE + "/zeta?namespace=other").asString()))
                .containsExactly("other/plain");
        assertThat(as(alice).get(ROUTE + "/nope").asString()).isEqualTo("[]");
        // "0" is empty to PHP, so it filters nothing
        assertThat(names(as(alice).get(ROUTE + "?namespace=0").asString()))
                .containsExactly("acme/notice", "acme/dyn", "acme/full", "other/plain");
        assertThat(names(as(alice).get(ROUTE + "/0").asString()))
                .containsExactly("acme/notice", "acme/dyn", "acme/full", "other/plain");
        // a collection's item is the item's answer
        assertThat(json(as(alice).get(ROUTE + "/acme").asString()).get(0).toString())
                .isEqualTo(json(NOTICE).toString());
        as(alice).head(ROUTE).then().statusCode(200).header("Allow", "GET").body(equalTo(""));
    }

    @Test
    void contextIsValidated() {
        as(alice)
                .get(ROUTE + "/acme/notice?context=foo")
                .then()
                .statusCode(400)
                .header("Allow", "GET")
                .body(equalTo(CONTEXT_FOO));
        as(alice).get(ROUTE + "?context=foo").then().statusCode(400).body(equalTo(CONTEXT_FOO));
        assertThat(as(alice).get(ROUTE + "/acme/notice?context=embed").asString())
                .isEqualTo(NOTICE);
    }

    @Test
    void optionsDescribeTheArguments() {
        JsonNode item = json(as(alice).options(ROUTE + "/acme/notice").asString());
        assertThat(item.get("endpoints").toString())
                .isEqualTo("[{\"methods\":[\"GET\"],\"args\":{\"name\":{\"description\":\"Block name.\","
                        + "\"type\":\"string\",\"required\":false},\"namespace\":{\"description\":\"Block namespace.\","
                        + "\"type\":\"string\",\"required\":false},\"context\":{\"description\":\"Scope under which the"
                        + " request is made; determines fields present in response.\",\"type\":\"string\","
                        + "\"enum\":[\"view\",\"embed\",\"edit\"],\"default\":\"view\",\"required\":false}}}]");
        List<String> properties = new ArrayList<>();
        item.at("/schema/properties").fieldNames().forEachRemaining(properties::add);
        assertThat(item.at("/schema/title").asText()).isEqualTo("block-type");
        assertThat(properties)
                .containsExactly(
                        "api_version",
                        "title",
                        "name",
                        "description",
                        "icon",
                        "attributes",
                        "provides_context",
                        "uses_context",
                        "selectors",
                        "supports",
                        "category",
                        "is_dynamic",
                        "editor_script_handles",
                        "script_handles",
                        "view_script_handles",
                        "view_script_module_ids",
                        "editor_style_handles",
                        "style_handles",
                        "view_style_handles",
                        "styles",
                        "variations",
                        "textdomain",
                        "parent",
                        "ancestor",
                        "allowed_blocks",
                        "keywords",
                        "example",
                        "block_hooks",
                        "editor_script",
                        "script",
                        "view_script",
                        "editor_style",
                        "style");
        JsonNode collection = json(as(alice).options(ROUTE + "/acme").asString());
        assertThat(collection.at("/endpoints/0/args").toString())
                .isEqualTo("{\"context\":{\"description\":\"Scope under which the request is made; determines fields"
                        + " present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],"
                        + "\"default\":\"view\",\"required\":false},\"namespace\":{\"description\":\"Block"
                        + " namespace.\",\"type\":\"string\",\"required\":false}}");
    }
}
