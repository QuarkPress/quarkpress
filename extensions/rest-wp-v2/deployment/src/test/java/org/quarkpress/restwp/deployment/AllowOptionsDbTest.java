package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestJson;

/** WordPress's Allow header per caller, its OPTIONS answer (route data in the help context) and pagination headers. */
class AllowOptionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.with(AllowOptionsDbTest.class, TestRoutes.class);

    static final String ROOT_LINK = "<http://localhost/wp-json/>; rel=\"https://api.w.org/\"";
    static final String THINGS = "http://localhost/wp-json/qp-test/v1/things";
    static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    long administrator;
    long editor;
    long author;
    long contributor;
    long subscriber;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        administrator = TestData.memberOfMain(data, "administrator").value();
        editor = TestData.memberOfMain(data, "editor").value();
        author = TestData.memberOfMain(data, "author").value();
        contributor = TestData.memberOfMain(data, "contributor").value();
        subscriber = TestData.memberOfMain(data, "subscriber").value();
    }

    @Test
    void allowListsThePermittedMethodsInRegistrationOrder() {
        Http.as(null).get("/wp-json/qp-test/v1/things/3").then().header("Allow", "GET");
        Http.as("test-" + administrator)
                .get("/wp-json/qp-test/v1/things/3")
                .then()
                .header("Allow", "GET, POST, PUT, PATCH, DELETE");
        Http.as("test-" + author).get("/wp-json/qp-test/v1/things/3").then().header("Allow", "GET, POST, PUT, PATCH");
        Http.as(null)
                .get("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(400)
                .header("Allow", nullValue());
        // an editor may also delete others' posts, which /guarded's DELETE asks for; a contributor may only edit posts
        Http.as("test-" + editor)
                .get("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(400)
                .header("Allow", "GET, DELETE");
        Http.as("test-" + contributor)
                .get("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(400)
                .header("Allow", "GET");
        Http.as(null).get("/wp-json/qp-test/v1/none").then().header("Allow", nullValue());
    }

    @Test
    void allowNamesExactlyTheMethodsTheCallerMayUse() {
        // both directions: a listed method is served, an unlisted one is refused, for every caller and route
        List<String> callers = new ArrayList<>(Arrays.asList((String) null));
        for (long user : new long[] {administrator, editor, author, contributor, subscriber}) {
            callers.add("test-" + user);
        }
        for (String path : List.of("/things/3", "/things", "/guarded")) {
            for (String caller : callers) {
                String allow = Http.as(caller).get("/wp-json/qp-test/v1" + path).header("Allow");
                List<String> allowed = allow == null ? List.of() : Arrays.asList(allow.split(", "));
                for (String method : METHODS) {
                    Response response = Http.as(caller).request(method, "/wp-json/qp-test/v1" + path);
                    assertThat(response.statusCode() == 200)
                            .as(
                                    "%s %s by %s (Allow: %s) answered %d",
                                    method, path, caller, allow, response.statusCode())
                            .isEqualTo(allowed.contains(method));
                    if (response.statusCode() != 404) {
                        // every answer of the matched route carries the caller's Allow, a refusal included
                        assertThat(response.header("Allow"))
                                .as("%s %s by %s", method, path, caller)
                                .isEqualTo(allow);
                    }
                }
            }
        }
    }

    @Test
    void errorsOfAMatchedRouteCarryAllow() {
        Http.as("test-" + author)
                .contentType("application/json")
                .body("{")
                .post("/wp-json/qp-test/v1/things/3")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_json"))
                .header("Allow", "GET, POST, PUT, PATCH");
        Http.as(null)
                .get("/wp-json/qp-test/v1/throws/boom")
                .then()
                .statusCode(500)
                .header("Allow", "GET");
        Http.as("test-" + subscriber)
                .delete("/wp-json/qp-test/v1/things/3")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"))
                .body("message", equalTo("Sorry, you are not allowed to do that."))
                .header("Allow", "GET");
        // an unmatched method on a known path is no match: no Allow
        Http.as("test-" + administrator)
                .put("/wp-json/qp-test/v1/things")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"))
                .header("Allow", nullValue());
    }

    @Test
    void optionsAnswersRouteDataInHelpContext() {
        Http.as(null)
                .options("/wp-json/qp-test/v1/none")
                .then()
                .statusCode(200)
                .body(equalTo("[]"))
                .header("Allow", nullValue());
        Response admin = Http.as("test-" + administrator).options("/wp-json/qp-test/v1/things");
        admin.then().statusCode(200).header("Allow", "GET, POST");
        JsonNode data = json(admin.asString());
        assertThat(fieldNames(data)).containsExactly("namespace", "methods", "endpoints", "schema", "_links");
        assertThat(data.get("methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        assertThat(data.at("/endpoints/0").toString())
                .startsWith("{\"methods\":[\"GET\"],\"allow_batch\":{\"v1\":true},\"args\":{");
        assertThat(data.at("/endpoints/0/args/per_page").toString())
                .isEqualTo("{\"description\":\"Maximum number of items to be"
                        + " returned in result set.\",\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":100,"
                        + "\"required\":false}");
        assertThat(data.at("/endpoints/1").toString())
                .isEqualTo("{\"methods\":[\"POST\"],\"allow_batch\":{\"v1\":true},\"args\":[]}");
        assertThat(data.get("schema")).isEqualTo(TestRoutes.thingSchema());
        assertThat(data.at("/_links/self/0/href").asText()).isEqualTo("http://localhost/wp-json/qp-test/v1/things");
        Response item = Http.as(null).options("/wp-json/qp-test/v1/things/3");
        item.then().statusCode(200).header("Allow", "GET");
        assertThat(json(item.asString()).has("_links")).isFalse();
        // the route data goes through _fields like any answer
        Http.as(null)
                .options("/wp-json/qp-test/v1/things/3?_fields=namespace")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body(equalTo("{\"namespace\":\"qp-test\\/v1\"}"));
        Http.as(null)
                .header("X-HTTP-Method-Override", "OPTIONS")
                .post("/wp-json/qp-test/v1/things")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body("namespace", equalTo("qp-test/v1"));
        Http.as("test-" + administrator)
                .header("X-HTTP-Method-Override", "OPTIONS")
                .post("/wp-json/qp-test/v1/none")
                .then()
                .statusCode(200)
                .header("Allow", nullValue())
                .body(equalTo("[]"));
        // a route none of whose endpoints is shown: WordPress's null data, sent as no body; Allow still computed
        Http.as(null)
                .options("/wp-json/qp-test/v1/hidden")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(emptyString());
        Http.as(null).get("/wp-json/qp-test/v1/hidden").then().statusCode(200).header("Allow", "GET");
        // nothing is validated and no handler runs; Allow is still the caller's
        Http.as("test-" + editor)
                .contentType("application/json")
                .body("{")
                .options("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(200)
                .header("Allow", "GET, DELETE")
                .body("namespace", equalTo("qp-test/v1"));
        // an invalid token is refused before OPTIONS is answered
        Http.as("invalid")
                .options("/wp-json/qp-test/v1/things")
                .then()
                .statusCode(401)
                .header("Allow", nullValue());
    }

    @Test
    void headGetsThePaginationHeaders() {
        Http.as(null)
                .head("/wp-json/qp-test/v1/things?per_page=1")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "3")
                .header("X-WP-TotalPages", "3")
                .header("Link", "<" + THINGS + "?per_page=1&page=2>; rel=\"next\"")
                .header("Allow", "GET")
                .body(emptyString());
    }

    @Test
    void thePaginationLinkReplacesTheApiRootLink() {
        Response first = Http.as(null).get("/wp-json/qp-test/v1/things?per_page=1");
        first.then().statusCode(200).body(equalTo("[{\"id\":1}]"));
        assertThat(first.headers().getValues("Link"))
                .containsExactly("<" + THINGS + "?per_page=1&page=2>; rel=\"next\"");
        Response middle = Http.as(null).get("/wp-json/qp-test/v1/things?per_page=1&page=2");
        middle.then().body(equalTo("[{\"id\":2}]"));
        assertThat(middle.headers().getValues("Link"))
                .containsExactly("<" + THINGS + "?per_page=1&page=1>; rel=\"prev\", <" + THINGS
                        + "?per_page=1&page=3>; rel=\"next\"");
        // a one-page answer keeps the API root's Link
        Response one = Http.as(null).get("/wp-json/qp-test/v1/things");
        one.then().header("X-WP-Total", "3").header("X-WP-TotalPages", "1");
        assertThat(one.headers().getValues("Link")).containsExactly(ROOT_LINK);
        // Part D 15: the envelope holds the totals as JSON integers, then Allow; the HTTP Link stays the root's
        Response enveloped = Http.as(null).get("/wp-json/qp-test/v1/things?_envelope");
        enveloped
                .then()
                .statusCode(200)
                .body(equalTo("{\"body\":[{\"id\":1},{\"id\":2},{\"id\":3}],\"status\":200,\"headers\":"
                        + "{\"X-WP-Total\":3,\"X-WP-TotalPages\":1,\"Allow\":\"GET\"}}"));
        assertThat(enveloped.headers().getValues("Link")).containsExactly(ROOT_LINK);
        Http.as(null)
                .get("/wp-json/qp-test/v1/things?per_page=2&_envelope")
                .then()
                .body(equalTo("{\"body\":[{\"id\":1},{\"id\":2}],\"status\":200,\"headers\":{\"X-WP-Total\":3,"
                        + "\"X-WP-TotalPages\":2,\"Link\":\"<" + THINGS.replace("/", "\\/")
                        + "?per_page=2&_envelope&page=2>; rel=\\\"next\\\"\",\"Allow\":\"GET\"}}"));
    }

    @Test
    void linksNameTheRouteAndCarryTheSanitizedQuery() {
        // the route's own spelling, whatever case the request used; values as the args' sanitizers left them
        Response response = Http.as(null)
                .urlEncodingEnabled(false)
                .get("/wp-json/qp-test/v1/THINGS?per_page=01&search=%3Cb%3Ex%3C%2Fb%3E");
        assertThat(response.headers().getValues("Link"))
                .containsExactly("<" + THINGS + "?per_page=1&search=x&page=2>; rel=\"next\"");
        // a page past the end: [] with prev to the last page
        Response past = Http.as(null).get("/wp-json/qp-test/v1/things?page=9&context=embed");
        past.then().statusCode(200).body(equalTo("[]"));
        assertThat(past.headers().getValues("Link"))
                .containsExactly("<" + THINGS + "?page=1&context=embed>; rel=\"prev\"");
    }

    @Test
    void offsetCountsOnlyWhereTheEndpointDeclaresIt() {
        Response declared = Http.as(null).get("/wp-json/qp-test/v1/offset-things?per_page=1&offset=1");
        declared.then().statusCode(200).body(equalTo("[{\"id\":2}]"));
        // offset 1 at 1 per page is page 2
        String offsetThings = "http://localhost/wp-json/qp-test/v1/offset-things";
        assertThat(declared.headers().getValues("Link"))
                .containsExactly("<" + offsetThings + "?per_page=1&offset=1&page=1>; rel=\"prev\", <" + offsetThings
                        + "?per_page=1&offset=1&page=3>; rel=\"next\"");
        // non-negative, as WordPress's queries take it
        Http.as(null)
                .get("/wp-json/qp-test/v1/offset-things?per_page=1&offset=-1")
                .then()
                .body(equalTo("[{\"id\":2}]"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/offset-things?per_page=1&page=3")
                .then()
                .body(equalTo("[{\"id\":3}]"));
        // /things declares no offset: it is ignored
        Response undeclared = Http.as(null).get("/wp-json/qp-test/v1/things?per_page=1&offset=1");
        undeclared.then().body(equalTo("[{\"id\":1}]"));
        assertThat(undeclared.headers().getValues("Link"))
                .containsExactly("<" + THINGS + "?per_page=1&offset=1&page=2>; rel=\"next\"");
    }

    @Test
    void allowOnTheOtherEntryForms() {
        for (String url : List.of("/?rest_route=/qp-test/v1/things/3", "/index.php/wp-json/qp-test/v1/things/3")) {
            Http.as("test-" + author).get(url).then().statusCode(200).header("Allow", "GET, POST, PUT, PATCH");
            Http.as(null).get(url).then().statusCode(200).header("Allow", "GET");
            Http.as("test-" + administrator)
                    .options(url)
                    .then()
                    .statusCode(200)
                    .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                    .body("namespace", equalTo("qp-test/v1"));
        }
    }

    static JsonNode json(String body) {
        try {
            return RestJson.READER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
