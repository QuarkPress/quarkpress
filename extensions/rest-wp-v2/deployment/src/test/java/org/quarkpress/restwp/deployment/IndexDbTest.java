package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The site index ({@code GET /}) and the namespace indexes, as WordPress answers them (Part D 19): the site's data in
 * WordPress's key order, every route's data in registration order with each namespace index right before its
 * namespace's first route, and the site logo and icon fields with their links unless {@code _fields} leaves them out.
 */
class IndexDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(IndexDbTest.class, TestRoutes.class);

    long editor;
    long administrator;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        editor = TestData.memberOfMain(data, "editor").value();
        administrator = TestData.memberOfMain(data, "administrator").value();
    }

    @Test
    void theSiteIndexInWordPresssOrder() {
        JsonNode index = json(Http.as(null).get("/wp-json/").asString());
        assertThat(fieldNames(index))
                .containsExactly(
                        "name",
                        "description",
                        "url",
                        "home",
                        "gmt_offset",
                        "timezone_string",
                        "page_for_posts",
                        "page_on_front",
                        "show_on_front",
                        "namespaces",
                        "authentication",
                        "routes",
                        "site_logo",
                        "site_icon",
                        "site_icon_url",
                        "_links");
        // type-strict: null or "0" fails (the PHP-like reader reads integers as longs)
        assertThat(index.get("site_logo")).isEqualTo(LongNode.valueOf(0));
        assertThat(index.get("site_icon")).isEqualTo(LongNode.valueOf(0));
        assertThat(index.get("site_icon_url")).isEqualTo(TextNode.valueOf(""));
        assertThat(index.get("page_for_posts")).isEqualTo(LongNode.valueOf(0));
        assertThat(index.get("page_on_front")).isEqualTo(LongNode.valueOf(0));
        assertThat(index.get("show_on_front")).isEqualTo(TextNode.valueOf("posts"));
        assertThat(index.get("url")).isEqualTo(TextNode.valueOf("http://localhost"));
        assertThat(index.get("home")).isEqualTo(TextNode.valueOf("http://localhost"));
        // OIDC disabled in this app
        assertThat(index.get("authentication").toString()).isEqualTo("[]");
        assertThat(index.at("/_links").toString())
                .isEqualTo("{\"help\":[{\"href\":\"https://developer.wordpress.org/rest-api/\"}]}");
        // Ruling T14a: the active theme link waits for the themes routes
        assertThat(index.at("/_links").has("wp:active-theme")).isFalse();
        assertThat(index.at("/routes/~1wp~1v2~1types/_links/self/0/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/types");
        // each namespace index sits right before its namespace's first route, as WordPress inserts it
        List<String> routes = fieldNames(index.get("routes"));
        assertThat(routes).startsWith("/", "/wp/v2", "/wp/v2/types", "/wp/v2/types/(?P<type>[\\w-]+)");
        assertThat(routes.get(routes.indexOf("/qp-test/v1") + 1)).startsWith("/qp-test/v1/");
        assertThat(routes.indexOf("/qp-test/v1"))
                .isGreaterThan(routes.indexOf("/wp/v2/taxonomies/(?P<taxonomy>[\\w-]+)"));
        // Part D 19: gmt_offset is the JSON string "0" on a fresh site (type-strict)
        assertThat(index.get("gmt_offset")).isEqualTo(TextNode.valueOf("0"));
        // namespaces lists wp/v2 and the test namespace in registration order
        assertThat(index.get("namespaces").toString()).isEqualTo("[\"wp/v2\",\"qp-test/v1\"]");
        // the route data as WordPress describes the index routes (Part D 19)
        assertThat(index.at("/routes/~1").toString())
                .isEqualTo("{\"namespace\":\"\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],"
                        + "\"args\":{\"context\":{\"default\":\"view\",\"required\":false}}}],"
                        + "\"_links\":{\"self\":[{\"href\":\"http://localhost/wp-json/\"}]}}");
        assertThat(index.at("/routes/~1wp~1v2").toString())
                .isEqualTo("{\"namespace\":\"wp/v2\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],"
                        + "\"args\":{\"namespace\":{\"default\":\"wp/v2\",\"required\":false},"
                        + "\"context\":{\"default\":\"view\",\"required\":false}}}],"
                        + "\"_links\":{\"self\":[{\"href\":\"http://localhost/wp-json/wp/v2\"}]}}");
        assertThat(index.at("/routes/~1wp~1v2~1types~1(?P<type>[\\w-]+)").toString())
                .isEqualTo("{\"namespace\":\"wp/v2\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],"
                        + "\"args\":{\"type\":{\"description\":\"An alphanumeric identifier for the post type.\","
                        + "\"type\":\"string\",\"required\":false},\"context\":{\"description\":\"Scope under which"
                        + " the request is made; determines fields present in response.\",\"type\":\"string\","
                        + "\"enum\":[\"view\",\"embed\",\"edit\"],\"default\":\"view\",\"required\":false}}}]}");
        assertThat(index.at("/routes/~1wp~1v2~1taxonomies/endpoints/0/args").toString())
                .isEqualTo(
                        "{\"context\":{\"description\":\"Scope under which the request is made; determines"
                                + " fields present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],"
                                + "\"default\":\"view\",\"required\":false},\"type\":{\"description\":\"Limit results to"
                                + " taxonomies associated with a specific post type.\",\"type\":\"string\",\"required\":false}}");
        Http.as(null).get("/wp-json/").then().statusCode(200).header("Allow", "GET");
    }

    @Test
    void theIndexNeverCarriesClientSideMediaKeys() {
        // Known difference (Ruling T2c, README D15, parity known-differences file): WordPress gave image_sizes,
        // image_size_threshold, image_strip_meta and image_max_bit_depth to bob (editor) because the harness on
        // localhost is a secure context (Part D 19); QuarkPress never enables client-side media processing, so for
        // every caller the keys are absent and site_logo follows show_on_front, namespaces, authentication, routes
        for (String bearer : Arrays.asList(null, "test-" + editor, "test-" + administrator)) {
            JsonNode index = json(Http.as(bearer).get("/wp-json/").asString());
            assertThat(fieldNames(index))
                    .doesNotContain("image_sizes", "image_size_threshold", "image_strip_meta", "image_max_bit_depth");
            List<String> names = fieldNames(index);
            assertThat(names.get(names.indexOf("routes") + 1)).isEqualTo("site_logo");
        }
    }

    @Test
    void fieldsSelectTheSiteLogoFieldsAndHelpAddsSchemas() {
        assertThat(fieldNames(json(
                        Http.as(null).get("/wp-json/?_fields=name,description").asString())))
                .containsExactly("name", "description");
        assertThat(fieldNames(
                        json(Http.as(null).get("/wp-json/?_fields=site_logo").asString())))
                .containsExactly("site_logo");
        assertThat(fieldNames(json(
                        Http.as(null).get("/wp-json/?_fields=site_icon_url").asString())))
                .containsExactly("site_icon_url");
        // _links selected: the help link and the logo and icon fields, reduced to what was asked for
        assertThat(Http.as(null).get("/wp-json/?_fields=_links").asString())
                .isEqualTo(
                        "{\"_links\":{\"help\":[{\"href\":\"https:\\/\\/developer.wordpress.org\\/rest-api\\/\"}]}}");
        assertThat(fieldNames(json(Http.as(null).get("/wp-json/?_fields=name").asString())))
                .containsExactly("name");
        assertThat(json(Http.as(null).get("/wp-json/?context=help").asString())
                        .at("/routes/~1wp~1v2~1types")
                        .has("schema"))
                .isTrue();
        assertThat(json(Http.as(null).get("/wp-json/").asString())
                        .at("/routes/~1wp~1v2~1types")
                        .has("schema"))
                .isFalse();
    }

    @Test
    void aSetLogoAndIconAddEmbeddableMediaLinks() {
        Jdbc.option(TestData.MAIN, "site_logo", "\"12\"");
        Jdbc.option(TestData.MAIN, "site_icon", "7");
        try {
            JsonNode index = json(Http.as(null).get("/wp-json/").asString());
            assertThat(index.get("site_logo")).isEqualTo(LongNode.valueOf(12));
            assertThat(index.get("site_icon")).isEqualTo(LongNode.valueOf(7));
            // no media module: no icon URL
            assertThat(index.get("site_icon_url")).isEqualTo(TextNode.valueOf(""));
            assertThat(index.get("_links").toString())
                    .isEqualTo(
                            "{\"help\":[{\"href\":\"https://developer.wordpress.org/rest-api/\"}],"
                                    + "\"wp:featuredmedia\":[{\"embeddable\":true,\"type\":\"site_logo\","
                                    + "\"href\":\"http://localhost/wp-json/wp/v2/media/12\"},"
                                    + "{\"embeddable\":true,\"type\":\"site_icon\","
                                    + "\"href\":\"http://localhost/wp-json/wp/v2/media/7\"}],"
                                    + "\"curies\":[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]}");
            // only the logo asked for: only its link, kept because links are attached after the reduction
            JsonNode logo =
                    json(Http.as(null).get("/wp-json/?_fields=site_logo").asString());
            assertThat(fieldNames(logo)).containsExactly("site_logo", "_links");
            assertThat(logo.at("/_links/wp:featuredmedia").size()).isEqualTo(1);
            assertThat(logo.at("/_links/wp:featuredmedia/0/type").asText()).isEqualTo("site_logo");
        } finally {
            Jdbc.update(
                    Jdbc.dataSource(),
                    "delete from qp_option where site_id = ? and name in ('site_logo', 'site_icon')",
                    TestData.MAIN.value());
        }
    }

    @Test
    void theNamespaceIndex() {
        JsonNode v2 = json(Http.as(null).get("/wp-json/wp/v2").asString());
        assertThat(fieldNames(v2)).containsExactly("namespace", "routes", "_links");
        assertThat(v2.get("namespace")).isEqualTo(TextNode.valueOf("wp/v2"));
        assertThat(v2.at("/_links/up/0/href").asText()).isEqualTo("http://localhost/wp-json/");
        // the namespace's routes only, its own index first
        assertThat(fieldNames(v2.get("routes")))
                .containsExactly(
                        "/wp/v2",
                        "/wp/v2/types",
                        "/wp/v2/types/(?P<type>[\\w-]+)",
                        "/wp/v2/statuses",
                        "/wp/v2/statuses/(?P<status>[\\w-]+)",
                        "/wp/v2/taxonomies",
                        "/wp/v2/taxonomies/(?P<taxonomy>[\\w-]+)",
                        "/wp/v2/categories",
                        "/wp/v2/categories/(?P<id>[\\d]+)",
                        "/wp/v2/tags",
                        "/wp/v2/tags/(?P<id>[\\d]+)",
                        "/wp/v2/users",
                        "/wp/v2/users/(?P<id>[\\d]+)",
                        "/wp/v2/users/me",
                        "/wp/v2/settings");
        // Part D 19
        Http.as(null)
                .get("/wp-json/wp/v2?namespace=nope")
                .then()
                .statusCode(404)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_invalid_namespace\","
                        + "\"message\":\"The specified namespace could not be found.\",\"data\":{\"status\":404}}"));
        // the query beats the default: another namespace's index
        Http.as(null)
                .get("/wp-json/wp/v2?namespace=qp-test/v1")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("qp-test/v1"));
        JsonNode test = json(Http.as(null).get("/wp-json/qp-test/v1/").asString());
        assertThat(fieldNames(test.get("routes")).get(0)).isEqualTo("/qp-test/v1");
        assertThat(json(Http.as(null).get("/wp-json/wp/v2?context=help").asString())
                        .at("/routes/~1wp~1v2~1statuses/schema/title")
                        .asText())
                .isEqualTo("status");
        // OPTIONS on the namespace route describes it (Part D OPTIONS /wp/v2)
        Http.as(null)
                .options("/wp-json/wp/v2")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo("{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],"
                        + "\"args\":{\"namespace\":{\"default\":\"wp\\/v2\",\"required\":false},"
                        + "\"context\":{\"default\":\"view\",\"required\":false}}}],"
                        + "\"_links\":{\"self\":[{\"href\":\"http:\\/\\/localhost\\/wp-json\\/wp\\/v2\"}]}}"));
    }

    @Test
    void headAnswersTheIndexesWithoutBodies() {
        Http.as(null)
                .head("/wp-json/")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo(""));
        Http.as(null)
                .head("/wp-json/wp/v2")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("Link", "<http://localhost/wp-json/>; rel=\"https://api.w.org/\"")
                .body(equalTo(""));
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
