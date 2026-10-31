package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.SiteScope;

/** The REST server's order of work, its error object, its safety net for service exceptions and its token refusal. */
class DispatchDbTest {

    /** The largest body the test application accepts, in KiB. */
    static final int BODY_LIMIT = 64;

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    DispatchDbTest.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class,
                    RawHttp.class,
                    RawHttp.Reply.class)
            .overrideConfigKey("quarkus.http.limits.max-body-size", BODY_LIMIT + "K");

    static final String NO_ROUTE = "{\"code\":\"rest_no_route\",\"message\":\"No route was found matching the URL and"
            + " request method.\",\"data\":{\"status\":404}}";

    static final String FORBIDDEN =
            "{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\",\"data\":{\"status\":";

    @Inject
    SiteScope scope;

    @Inject
    FeatureToggles toggles;

    UserId subscriber;
    UserId author;
    UserId editor;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        subscriber = TestData.memberOfMain(data, "subscriber");
        author = TestData.memberOfMain(data, "author");
        editor = TestData.memberOfMain(data, "editor");
    }

    @Test
    void unknownPathsAndUnsupportedMethodsAreBoth404NoRoute() {
        Http.as(null).get("/wp-json/qp-test/v1/none").then().statusCode(404).body(equalTo(NO_ROUTE));
        Http.as(null).delete("/wp-json/qp-test/v1/items").then().statusCode(404).body(equalTo(NO_ROUTE));
    }

    @Test
    void validationRunsBeforePermission() {
        Http.as(null)
                .get("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): per_page"));
        // the sanitizing phase too runs before the permission
        Http.as(null)
                .get("/wp-json/qp-test/v1/guarded?order=up")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.order", equalTo("order is not one of asc and desc."));
        Http.as("test-" + editor.value())
                .get("/wp-json/qp-test/v1/guarded?per_page=5&order=desc")
                .then()
                .statusCode(200)
                .body("per_page", equalTo(5))
                .body("order", equalTo("desc"));
    }

    @Test
    void aFalsePermissionIsRestForbiddenWithTheAuthCode() {
        Http.as(null).get("/wp-json/qp-test/v1/guarded").then().statusCode(401).body(equalTo(FORBIDDEN + "401}}"));
        Http.as("test-" + subscriber.value())
                .get("/wp-json/qp-test/v1/guarded")
                .then()
                .statusCode(403)
                .body(equalTo(FORBIDDEN + "403}}"));
        Http.as("test-" + subscriber.value())
                .get("/wp-json/qp-test/v1/denied")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_view"))
                .body("message", equalTo("Nope."));
        Http.as(null)
                .get("/wp-json/qp-test/v1/denied")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_view"));
    }

    @Test
    void theIdentityAndTheOverrideReachTheDeleteEndpointThroughEveryEntryForm() {
        // GET with ?_method=DELETE through rest_route, and a form POST to the site's path carrying rest_route: both are
        // the DELETE endpoint, whose permission (delete_others_posts) an author lacks although GET's (edit_posts)
        // passes
        Http.as("test-" + author.value())
                .get("/?rest_route=/qp-test/v1/guarded")
                .then()
                .statusCode(200)
                .body("method", equalTo("GET"));
        for (String bearer : new String[] {null, "test-" + subscriber.value(), "test-" + author.value()}) {
            int status = bearer == null ? 401 : 403;
            Http.as(bearer)
                    .get("/?rest_route=/qp-test/v1/guarded&_method=DELETE")
                    .then()
                    .statusCode(status)
                    .body(equalTo(FORBIDDEN + status + "}}"));
            Http.as(bearer)
                    .contentType("application/x-www-form-urlencoded")
                    .body("rest_route=/qp-test/v1/guarded")
                    .post("/?_method=DELETE")
                    .then()
                    .statusCode(status)
                    .body(equalTo(FORBIDDEN + status + "}}"));
        }
        Http.as("test-" + editor.value())
                .get("/?rest_route=/qp-test/v1/guarded&_method=DELETE")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"));
        Http.as("test-" + editor.value())
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/guarded")
                .post("/?_method=DELETE")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"));
    }

    @Test
    void theOverrideHeaderReachesTheDeleteEndpointsPermission() {
        Http.as("test-" + author.value())
                .header("X-HTTP-Method-Override", "DELETE")
                .get("/wp-json/qp-test/v1/guarded")
                .then()
                .statusCode(403)
                .body(equalTo(FORBIDDEN + "403}}"));
        Http.as(null)
                .header("X-HTTP-Method-Override", "delete")
                .get("/wp-json/qp-test/v1/guarded")
                .then()
                .statusCode(401)
                .body(equalTo(FORBIDDEN + "401}}"));
        Http.as("test-" + editor.value())
                .header("X-HTTP-Method-Override", "DELETE")
                .post("/wp-json/qp-test/v1/guarded")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"));
    }

    @Test
    void aRestRouteRefusalPrecedesTheTokenError() {
        // WordPress reads rest_route before it authenticates: the refusal wins over an invalid token, also when the
        // route comes from the form body
        Http.as("invalid")
                .get("/?rest_route[]=x")
                .then()
                .statusCode(400)
                .header("Content-Type", "text/html; charset=UTF-8")
                .header("WWW-Authenticate", nullValue())
                .body(containsString("The REST route parameter must be a string."));
        Http.as("invalid")
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/json")
                .post("/?rest_route=/qp-test/v1/items")
                .then()
                .statusCode(400)
                .header("WWW-Authenticate", nullValue())
                .body(containsString("A variable mismatch has been detected."));
        Http.as("invalid")
                .header("Accept", "application/json")
                .contentType("application/x-www-form-urlencoded")
                .body("a=1&rest_route[]=x")
                .post("/")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_path_invalid_type\",\"message\":\"The REST route parameter must be a"
                        + " string.\",\"data\":{\"status\":400},\"additional_errors\":[]}"));
        // the same request with a valid form route still gets the token error
        Http.as("invalid")
                .contentType("application/x-www-form-urlencoded")
                .body("a=1&rest_route=/qp-test/v1/json")
                .post("/")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_invalid_token"));
    }

    @Test
    void aStreamedFormBodyOverTheLimitIsRefusedAtOnce() throws IOException {
        // a chunked form POST with an invalid token, whose body passes the limit and never ends: answered 413 and the
        // connection closed as soon as the limit is passed, never drained
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST / HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer invalid\r\n"
                            + "Content-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            int size = BODY_LIMIT * 1024 + 1;
            out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] chunk = new byte[size];
            Arrays.fill(chunk, (byte) 'a');
            out.write(chunk);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            // no last chunk: the body never ends
            long start = System.nanoTime();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(reply);
            String text = reply.toString(StandardCharsets.ISO_8859_1);
            assertThat(text).startsWith("HTTP/1.1 413");
            assertThat(text).doesNotContainIgnoringCase("WWW-Authenticate");
            // the server closed the connection (transferTo returned) well before the read timeout
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
        }
        // a form body within the limit is still read for its rest_route
        Http.as("invalid")
                .contentType("application/x-www-form-urlencoded")
                .body("a=" + "b".repeat(BODY_LIMIT * 1024 - 100) + "&rest_route[]=x")
                .post("/")
                .then()
                .statusCode(400)
                .body(containsString("The REST route parameter must be a string."));
    }

    @Test
    void aJsonBodyOverTheLimitIsRefusedBeforeAnyHandlerRuns() {
        // the normal path: a valid caller, a body of a declared length one byte over the limit
        StringBuilder body = new StringBuilder("{\"a\":\"");
        body.append("b".repeat(BODY_LIMIT * 1024 + 1 - body.length() - 2)).append("\"}");
        assertThat(body).hasSize(BODY_LIMIT * 1024 + 1);
        int before = TestRoutes.JSON_CALLS.get();
        Http.as("test-" + editor.value())
                .contentType("application/json")
                .body(body.toString())
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(413);
        assertThat(TestRoutes.JSON_CALLS.get()).isEqualTo(before);
        // one byte less is served
        String within = "{\"a\":\"" + "b".repeat(BODY_LIMIT * 1024 - 8) + "\"}";
        assertThat(within).hasSize(BODY_LIMIT * 1024);
        Http.as("test-" + editor.value())
                .contentType("application/json")
                .body(within)
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(200);
        assertThat(TestRoutes.JSON_CALLS.get()).isEqualTo(before + 1);
    }

    @Test
    void malformedJsonIsRefusedOnlyAfterAMatch() {
        Http.as(null)
                .contentType("application/json")
                .body("{nope")
                .get("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_json\",\"message\":\"Invalid JSON body passed.\",\"data\":"
                        + "{\"status\":400,\"json_error_code\":4,\"json_error_message\":\"Syntax error\"}}"));
        Http.as(null)
                .contentType("application/json")
                .body("{nope")
                .get("/wp-json/qp-test/v1/none")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as(null)
                .contentType("text/plain")
                .body("{nope")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(200);
        Http.as(null)
                .contentType("application/json")
                .body("[".repeat(600) + "]".repeat(600))
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_json"))
                .body("data.json_error_code", equalTo(1))
                .body("data.json_error_message", equalTo("Maximum stack depth exceeded"));
        // deeper than Jackson's own limit: PHP's depth error still
        Http.as(null)
                .contentType("application/json")
                .body("[".repeat(1500) + "]".repeat(1500))
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(400)
                .body("data.json_error_code", equalTo(1));
        // a lone surrogate escape (PHP's code 10) and a closing bracket of the wrong kind (code 2)
        Http.as(null)
                .contentType("application/json")
                .body("{\"a\":\"\\ud800\"}")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(400)
                .body("data.json_error_code", equalTo(10))
                .body("data.json_error_message", equalTo("Single unpaired UTF-16 surrogate in unicode escape"));
        Http.as(null)
                .contentType("application/json")
                .body("[1}")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(400)
                .body("data.json_error_code", equalTo(2));
        // the check runs before the permission and the arguments
        Http.as(null)
                .contentType("application/json")
                .body("{nope")
                .get("/wp-json/qp-test/v1/guarded?per_page=0")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_json"));
        Http.as(null)
                .contentType("application/json")
                .body("{\"a\":[1,2]}")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(200)
                .body(equalTo("{\"a\":[1,2]}"));
    }

    @Test
    void m1ExceptionsAreMappedAsASafetyNet() {
        expect("forbidden", 403, "rest_forbidden", "Sorry, you are not allowed to do that.");
        expect("unauthorized", 401, "rest_forbidden", "Sorry, you are not allowed to do that.");
        expect("capability-denied", 403, "rest_forbidden", "Sorry, you are not allowed to do that.");
        expect("capability-denied-anonymous", 401, "rest_forbidden", "Sorry, you are not allowed to do that.");
        expect("feature-disabled", 404, "rest_no_route", "No route was found matching the URL and request method.");
        expect(
                        "duplicate-term-slug",
                        500,
                        "duplicate_term_slug",
                        "The slug &#8220;news&#8221; is already in use by another term.")
                .body("data", nullValue());
        expect("invalid-post", 500, "invalid_post", "Invalid post ID.").body("data", nullValue());
        expect("invalid-revision", 500, "rest_post_invalid_id", "Invalid revision ID.")
                .body("data", nullValue());
        expect("stale-version", 409, "quarkpress_conflict", "The item was changed by another request.");
        expect("wrapped-stale-version", 409, "quarkpress_conflict", "The item was changed by another request.");
        expect("rest", 418, "rest_custom", "Custom.");
        for (String kind : List.of("unexpected", "stack-overflow")) {
            Http.as(null)
                    .get("/wp-json/qp-test/v1/throws/" + kind)
                    .then()
                    .statusCode(500)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(equalTo("{\"code\":\"internal_server_error\",\"message\":\"There has been a critical error"
                            + " on this website.\",\"data\":{\"status\":500}}"));
        }
        Http.as(null)
                .get("/wp-json/qp-test/v1/throws/term-exists")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"term_exists\",\"message\":\"A term with the name provided already exists in"
                        + " this taxonomy.\",\"data\":{\"status\":400,\"term_id\":7},\"additional_data\":[7,7]}"));
    }

    private static ValidatableResponse expect(String kind, int status, String code, String message) {
        return Http.as(null)
                .get("/wp-json/qp-test/v1/throws/" + kind)
                .then()
                .statusCode(status)
                .body("code", equalTo(code))
                .body("message", equalTo(message));
    }

    @Test
    void anInvalidTokenIsRefusedBeforeMatchingEvenOnPublicAndUnknownRoutes() {
        for (String url : List.of(
                "/wp-json/",
                "/wp-json/qp-test/v1/items",
                "/wp-json/qp-test/v1/none",
                "/?rest_route=/qp-test/v1/items",
                "/wp-json/qp-test/v1/guarded?per_page=0")) {
            // Known difference (README D2; parity d06): WordPress serves a failed application password as
            // anonymous (REST verification list item 6); QuarkPress refuses an invalid or expired token before matching
            Http.as("invalid")
                    .get(url)
                    .then()
                    .statusCode(401)
                    .header("Allow", nullValue())
                    .header("WWW-Authenticate", nullValue())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(
                            equalTo(
                                    "{\"code\":\"rest_invalid_token\","
                                            + "\"message\":\"The access token is invalid or has expired.\",\"data\":{\"status\":401}}"));
        }
        Http.as("invalid")
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/items")
                .post("/")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", nullValue())
                .body("code", equalTo("rest_invalid_token"));
        // other application routes keep the security layer's own answer
        Http.as("invalid")
                .get("/it-plain/x/wp-json/ping")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", equalTo("Bearer"))
                .body(not(equalTo("pong")));
    }

    @Test
    void aNonStringRestRouteIsAJsonWpDieForJsonRequests() {
        // REST verification list item 2, JSON form (RoutingDbTest has the HTML form): Accept application/json gives
        // 400 application/json; charset=UTF-8, no-cache, no API Link
        Http.as(null)
                .header("Accept", "application/json")
                .get("/?rest_route[]=x")
                .then()
                .statusCode(400)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private")
                .header("Link", nullValue())
                .body(equalTo("{\"code\":\"rest_path_invalid_type\",\"message\":\"The REST route parameter must be a"
                        + " string.\",\"data\":{\"status\":400},\"additional_errors\":[]}"));
        Http.as(null)
                .contentType("application/json")
                .body("{}")
                .post("/wp-json/qp-test/v1?rest_route[]=x")
                .then()
                .statusCode(400)
                .header("Content-Type", "application/json; charset=UTF-8")
                .body("code", equalTo("rest_path_invalid_type"));
    }

    @Test
    void theSiteFeatureOffAnswers404NoRoute() {
        scope.run(TestData.MAIN, () -> toggles.setForSite("rest-wp-v2", false));
        try {
            Http.as(null)
                    .get("/wp-json/qp-test/v1/items")
                    .then()
                    .statusCode(404)
                    .body(equalTo(NO_ROUTE));
            Http.as(null)
                    .get("/?rest_route=/qp-test/v1/items")
                    .then()
                    .statusCode(404)
                    .body(equalTo(NO_ROUTE));
            Http.as("test-" + editor.value())
                    .get("/wp-json/qp-test/v1/guarded")
                    .then()
                    .statusCode(404)
                    .body(equalTo(NO_ROUTE));
        } finally {
            scope.run(TestData.MAIN, () -> toggles.clearForSite("rest-wp-v2"));
        }
        Http.as(null).get("/wp-json/qp-test/v1/items").then().statusCode(200).body(equalTo(TestRoutes.ITEMS));
    }
}
