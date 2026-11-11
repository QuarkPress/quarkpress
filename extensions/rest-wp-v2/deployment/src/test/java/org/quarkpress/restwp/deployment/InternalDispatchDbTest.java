package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.quarkpress.restwp.deployment.TestData.MAIN;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;

/**
 * The REST server's internal dispatch (as the editor route's preload map uses it), through the test route
 * {@code /qp-test/v1/internal}: the answer of a GET or OPTIONS of a route path with its query, for the caller of the
 * request that dispatches it, equals what the same request over HTTP answers, with only the answer's own headers;
 * and the route groups of block type namespaces, block names and stylesheets, matched over HTTP.
 */
class InternalDispatchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(InternalDispatchDbTest.class, TestRoutes.class);

    static final ObjectMapper PLAIN = new ObjectMapper();

    static final String INTERNAL = "/wp-json/qp-test/v1/internal";

    String bob;
    String erin;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        UserId editor = TestData.memberOfMain(data, "editor");
        bob = "test-" + editor.value();
        erin = "test-" + TestData.memberOfMain(data, "subscriber").value();
        TestData.post(data, MAIN, editor, "post", "publish");
    }

    @Test
    void aGetEqualsTheHttpAnswer() {
        JsonNode internal = internal(bob, "GET", "/wp/v2/types?context=view");
        Response http = Http.as(bob).get("/wp-json/wp/v2/types?context=view");
        assertThat(internal.get("status").intValue()).isEqualTo(200);
        assertThat(internal.get("data")).isEqualTo(json(http.asString()));
        assertThat(internal.get("data").has("post")).isTrue();
        assertThat(internal.at("/headers/Allow/0").textValue()).isNotNull().isEqualTo(http.header("Allow"));
        JsonNode item = internal(bob, "GET", "/wp/v2/posts?context=edit&per_page=1");
        Response itemHttp = Http.as(bob).get("/wp-json/wp/v2/posts?context=edit&per_page=1");
        assertThat(item.get("data")).isEqualTo(json(itemHttp.asString()));
        assertThat(item.at("/data/0/content/raw").isTextual()).isTrue();
        assertThat(item.at("/headers/X-WP-Total/0").textValue()).isEqualTo(itemHttp.header("X-WP-Total"));
        assertThat(item.at("/headers/X-WP-TotalPages/0").textValue()).isEqualTo(itemHttp.header("X-WP-TotalPages"));
    }

    @Test
    void optionsGivesRouteDataAndAllow() {
        JsonNode options = internal(bob, "OPTIONS", "/wp/v2/posts");
        assertThat(options.get("status").intValue()).isEqualTo(200);
        assertThat(options.at("/data/methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        assertThat(options.at("/data/namespace").textValue()).isEqualTo("wp/v2");
        assertThat(options.at("/headers/Allow").toString()).isEqualTo("[\"GET, POST\"]");
        // the method is upper-cased as WordPress's request does
        assertThat(internal(bob, "options", "/wp/v2/posts").get("data")).isEqualTo(options.get("data"));
    }

    @Test
    void refusalsKeepTheirStatus() {
        JsonNode refused = internal(erin, "GET", "/wp/v2/settings");
        assertThat(refused.get("status").intValue()).isEqualTo(403);
        assertThat(refused.at("/data/code").textValue()).isEqualTo("rest_forbidden");
        assertThat(refused.at("/data/message").textValue()).isEqualTo("Sorry, you are not allowed to do that.");
        assertThat(refused.at("/data/data/status").intValue()).isEqualTo(403);
        JsonNode anonymous = internal(null, "GET", "/wp/v2/settings");
        assertThat(anonymous.get("status").intValue()).isEqualTo(401);
        JsonNode missing = internal(bob, "GET", "/wp/v2/nothing-here");
        assertThat(missing.get("status").intValue()).isEqualTo(404);
        assertThat(missing.at("/data/code").textValue()).isEqualTo("rest_no_route");
        assertThat(missing.get("headers").size()).isZero();
    }

    @Test
    void onlyTheAnswersOwnHeaders() {
        JsonNode posts = internal(bob, "GET", "/wp/v2/posts");
        assertThat(fieldNames(posts.get("headers"))).containsExactly("X-WP-Total", "X-WP-TotalPages", "Allow");
        Response http = Http.as(bob).get("/wp-json/wp/v2/posts");
        // the HTTP answer has the fixed, no-cache and cross-origin headers, which the internal answer never gets
        assertThat(http.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(http.header("Cache-Control")).isNotNull();
        assertThat(http.header("Link")).contains("https://api.w.org/");
        assertThat(http.header("Access-Control-Expose-Headers")).isNotNull();
        JsonNode index = internal(bob, "GET", "/");
        assertThat(index.get("status").intValue()).isEqualTo(200);
        assertThat(fieldNames(index.get("headers"))).containsExactly("Allow");
    }

    @Test
    void fieldsAndEmbedApply() {
        JsonNode index = internal(bob, "GET", "/?_fields=name,url");
        assertThat(fieldNames(index.get("data"))).containsExactly("name", "url");
        JsonNode embedded = internal(bob, "GET", "/wp/v2/posts?_embed");
        assertThat(embedded.at("/data/0/_embedded/author/0/id").isNumber()).isTrue();
        assertThat(embedded.at("/data/0/_links/self/0/href").isTextual()).isTrue();
        JsonNode plain = internal(bob, "GET", "/wp/v2/posts");
        assertThat(plain.at("/data/0").has("_embedded")).isFalse();
        assertThat(plain.at("/data/0").has("_links")).isTrue();
    }

    @Test
    void theMethodOverrideIsIgnoredAndOtherMethodsAreGet() {
        JsonNode get = internal(bob, "GET", "/wp/v2/types?context=view");
        assertThat(internal(bob, "GET", "/wp/v2/types?context=view&_method=OPTIONS")
                        .get("data"))
                .isEqualTo(get.get("data"));
        assertThat(internal(bob, "POST", "/wp/v2/types?context=view").get("data"))
                .isEqualTo(get.get("data"));
        assertThat(internal(bob, "DELETE", "/wp/v2/settings").get("status").intValue())
                .isEqualTo(403);
    }

    @Test
    void aFailureEscapingTheHandlerIsWordPresssError() {
        // a StackOverflowError passes the handler's own catch (runtime exceptions only) and reaches the dispatch's
        JsonNode overflow = internal(bob, "GET", "/qp-test/v1/throws/stack-overflow");
        assertThat(overflow.get("status").intValue()).isEqualTo(500);
        assertThat(overflow.get("data").toString())
                .isEqualTo("{\"code\":\"internal_server_error\",\"message\":\"There has been a critical error on"
                        + " this website.\",\"data\":{\"status\":500}}");
        assertThat(overflow.get("headers").size()).isZero();
        assertThat(overflow.get("data"))
                .isEqualTo(json(Http.as(bob)
                        .get("/wp-json/qp-test/v1/throws/stack-overflow")
                        .asString()));
    }

    @Test
    void theRequestIsSecureAsTheCallerSays() {
        assertThat(internal(bob, "GET", "/qp-test/v1/secure").at("/data/secure").booleanValue())
                .isFalse();
        JsonNode secure = json(Http.as(bob)
                .queryParam("method", "GET")
                .queryParam("target", "/qp-test/v1/secure")
                .queryParam("secure", "1")
                .get(INTERNAL)
                .asString());
        assertThat(secure.at("/data/secure").booleanValue()).isTrue();
    }

    @Test
    void aPathWithoutALeadingSlashGetsOne() {
        JsonNode slashed = internal(bob, "GET", "/wp/v2/types?context=view");
        JsonNode bare = internal(bob, "GET", "wp/v2/types?context=view");
        assertThat(bare.get("status").intValue()).isEqualTo(200);
        assertThat(bare.get("data")).isEqualTo(slashed.get("data"));
        assertThat(bare.get("headers")).isEqualTo(slashed.get("headers"));
        assertThat(internal(bob, "OPTIONS", "wp/v2/posts").at("/data/methods").toString())
                .isEqualTo("[\"GET\",\"POST\"]");
    }

    @Test
    void theNewGroupsAreRoutes() {
        Http.as(null)
                .get("/wp-json/qp-test/v1/kinds/My_ns-2")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("My_ns-2"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/rendered/Core/Archives")
                .then()
                .statusCode(200)
                .body("name", equalTo("Core/Archives"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/styles/parent/child")
                .then()
                .statusCode(200)
                .body("stylesheet", equalTo("parent/child"));
        for (String path : List.of("kinds/a.b", "rendered/core", "rendered/core/a_b", "styles/a/b/c", "styles/a:b")) {
            Http.as(null)
                    .get("/wp-json/qp-test/v1/" + path)
                    .then()
                    .statusCode(404)
                    .body("code", equalTo("rest_no_route"));
        }
        JsonNode routes =
                json(Http.as(null).get("/wp-json/qp-test/v1").asString()).get("routes");
        assertThat(fieldNames(routes))
                .contains(
                        "/qp-test/v1/kinds/(?P<namespace>[a-zA-Z0-9_-]+)",
                        "/qp-test/v1/rendered/(?P<name>[a-z0-9-]+/[a-z0-9-]+)",
                        "/qp-test/v1/styles/(?P<stylesheet>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)");
        JsonNode options = json(
                Http.as(null).options("/wp-json/qp-test/v1/styles/quarkpress").asString());
        assertThat(options.get("methods").toString()).isEqualTo("[\"GET\"]");
    }

    /** The internal dispatch of {@code method} {@code target} for the caller {@code bearer} (null: anonymous). */
    private static JsonNode internal(String bearer, String method, String target) {
        Response response = Http.as(bearer)
                .queryParam("method", method)
                .queryParam("target", target)
                .get(INTERNAL);
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return json(response.asString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return PLAIN.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
