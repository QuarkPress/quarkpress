package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;

/** WordPress's fixed and no-cache headers, its public-read Vary, _pretty, _envelope, the JSONP refusal and HEAD. */
class HeadersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(HeadersDbTest.class, TestRoutes.class);

    static final String ROOT_LINK = "<http://localhost/wp-json/>; rel=\"https://api.w.org/\"";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String CACHE_CONTROL = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String JSONP_DISABLED =
            "{\"code\":\"rest_callback_disabled\",\"message\":\"JSONP support is disabled on this site.\"}";

    UserId editor;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        editor = TestData.memberOfMain(data, "editor");
    }

    @Test
    void everyAnswerCarriesWordPresssFixedHeaders() {
        for (Response response : List.of(
                Http.as(null).get("/wp-json/qp-test/v1/items"),
                Http.as(null).get("/wp-json/qp-test/v1/none"),
                Http.as(null).get("/wp-json/qp-test/v1/guarded?per_page=0"),
                Http.as("invalid").get("/wp-json/qp-test/v1/items"),
                Http.as(null).get("/wp-json/qp-test/v1/throws/boom"),
                Http.as("test-" + editor.value()).get("/wp-json/qp-test/v1/denied"))) {
            fixed(response.then());
        }
    }

    static ValidatableResponse fixed(ValidatableResponse response) {
        return response.header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Robots-Tag", "noindex")
                .header("Link", ROOT_LINK)
                .header("X-Content-Type-Options", "nosniff")
                .header("Access-Control-Expose-Headers", "X-WP-Total, X-WP-TotalPages, Link")
                .header(
                        "Access-Control-Allow-Headers",
                        "Authorization, X-WP-Nonce, Content-Disposition, Content-MD5, Content-Type")
                .header("Access-Control-Allow-Origin", nullValue())
                .header("X-Powered-By", nullValue());
    }

    @Test
    void anInvalidTokensErrorGoesThroughTheSamePipeline() {
        // QuarkPress's own code (README D2; WordPress has no such error, REST verification list item 6)
        Http.as("invalid")
                .get("/wp-json/qp-test/v1/items?_envelope")
                .then()
                .statusCode(200)
                .header("WWW-Authenticate", nullValue())
                .header("Vary", "Origin")
                .body("status", equalTo(401))
                .body("body.code", equalTo("rest_invalid_token"))
                .body("headers", equalTo(List.of()));
        assertThat(Http.as("invalid").get("/wp-json/qp-test/v1/items?_pretty").asString())
                .startsWith("{\n    \"code\": \"rest_invalid_token\",");
        fixed(Http.as("invalid").head("/wp-json/qp-test/v1/items").then())
                .statusCode(401)
                .body(emptyString());
        // the header override's 4xx gets no-cache on this path too; the query override does not
        Http.as("invalid")
                .header("X-HTTP-Method-Override", "DELETE")
                .post("/wp-json/qp-test/v1/items")
                .then()
                .statusCode(401)
                .header("Expires", EXPIRES)
                .header("Cache-Control", CACHE_CONTROL)
                .header("Vary", nullValue());
        Http.as("invalid")
                .post("/wp-json/qp-test/v1/items?_method=DELETE")
                .then()
                .statusCode(401)
                .header("Expires", nullValue());
        // the token error's envelope is no 4xx: no no-cache, as WordPress decides after enveloping
        Http.as("invalid")
                .header("X-HTTP-Method-Override", "DELETE")
                .post("/wp-json/qp-test/v1/items?_envelope")
                .then()
                .statusCode(200)
                .header("Expires", nullValue())
                .body("status", equalTo(401));
    }

    @Test
    void loggedInCallersAndHeaderOverridden4xxGetNoCacheHeaders() {
        Http.as("test-" + editor.value())
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("Expires", EXPIRES)
                .header("Cache-Control", CACHE_CONTROL)
                .header("Last-Modified", nullValue());
        Http.as("test-" + editor.value())
                .get("/wp-json/qp-test/v1/none")
                .then()
                .statusCode(404)
                .header("Expires", EXPIRES);
        Http.as(null).get("/wp-json/qp-test/v1/items").then().header("Expires", nullValue());
        Http.as(null)
                .header("X-HTTP-Method-Override", "DELETE")
                .get("/wp-json/qp-test/v1/items")
                .then()
                .statusCode(404)
                .header("Expires", EXPIRES)
                .header("Cache-Control", CACHE_CONTROL);
        Http.as(null)
                .get("/wp-json/qp-test/v1/items?_method=DELETE")
                .then()
                .statusCode(404)
                .header("Expires", nullValue());
        // _method wins over the header, so the method did not come from the header: no no-cache
        Http.as(null)
                .header("X-HTTP-Method-Override", "DELETE")
                .get("/wp-json/qp-test/v1/items?_method=DELETE")
                .then()
                .statusCode(404)
                .header("Expires", nullValue());
        Http.as(null)
                .header("X-HTTP-Method-Override", "DELETE")
                .get("/wp-json/qp-test/v1/items?_method[]=GET")
                .then()
                .statusCode(404)
                .header("Expires", EXPIRES);
        // a header override that ends in 200 or 5xx stays cacheable for anonymous callers
        Http.as(null)
                .header("X-HTTP-Method-Override", "GET")
                .post("/wp-json/qp-test/v1/items")
                .then()
                .statusCode(200)
                .header("Expires", nullValue());
        Http.as(null)
                .header("X-HTTP-Method-Override", "GET")
                .post("/wp-json/qp-test/v1/throws/boom")
                .then()
                .statusCode(500)
                .header("Expires", nullValue());
    }

    @Test
    void noCacheRemovesLastModifiedAndOwnHeadersReplaceFixedOnesInAnyCase() {
        // the route sets Last-Modified and a lower-case "link"
        Response anonymous = Http.as(null).get("/wp-json/qp-test/v1/headers");
        anonymous.then().statusCode(200).header("Last-Modified", TestRoutes.LAST_MODIFIED);
        assertThat(anonymous.headers().getValues("Link")).containsExactly(TestRoutes.NEXT_LINK);
        Response editors = Http.as("test-" + editor.value()).get("/wp-json/qp-test/v1/headers");
        editors.then().statusCode(200).header("Expires", EXPIRES).header("Last-Modified", nullValue());
        assertThat(editors.headers().getValues("Link")).containsExactly(TestRoutes.NEXT_LINK);
    }

    @Test
    void aFailureAfterTheAnswerStillSendsTheFixedHeaders() {
        // the route's body cannot be encoded (NaN): the 500 still carries the fixed, Vary and no-cache headers
        fixed(Http.as(null).get("/wp-json/qp-test/v1/nan").then())
                .statusCode(500)
                .header("Vary", "Origin")
                .body("code", equalTo("internal_server_error"));
        fixed(Http.as("test-" + editor.value()).get("/wp-json/qp-test/v1/nan").then())
                .statusCode(500)
                .header("Expires", EXPIRES)
                .header("Vary", nullValue());
        Http.as(null).head("/wp-json/qp-test/v1/nan").then().statusCode(500).body(emptyString());
    }

    @Test
    void varyOriginOnlyForAnonymousGetWithoutOrigin() {
        Http.as(null).get("/wp-json/qp-test/v1/items").then().header("Vary", "Origin");
        Http.as(null).get("/wp-json/qp-test/v1/none").then().header("Vary", "Origin");
        // REST verification list item 13
        Http.as(null).head("/wp-json/qp-test/v1/items").then().header("Vary", nullValue());
        Http.as("test-" + editor.value())
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("Vary", nullValue());
        Http.as(null).post("/wp-json/qp-test/v1/json").then().header("Vary", nullValue());
        // the real method counts, not the override
        Http.as(null)
                .header("X-HTTP-Method-Override", "GET")
                .post("/wp-json/qp-test/v1/items")
                .then()
                .statusCode(200)
                .header("Vary", nullValue());
        Http.as(null)
                .header("Origin", "https://x.test")
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Credentials", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
        // README D4: with no origin configured, not even "null" is reflected
        Http.as("test-" + editor.value())
                .header("Origin", "null")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Vary", nullValue());
    }

    @Test
    void prettyEnvelopeJsonpAndHead() {
        assertThat(Http.as(null).get("/wp-json/qp-test/v1/items?_pretty").asString())
                .startsWith("[\n    {");
        assertThat(Http.as(null).get("/wp-json/qp-test/v1/none?_pretty=0").asString())
                .isEqualTo("{\n    \"code\": \"rest_no_route\",\n    \"message\": \"No route was found matching the URL"
                        + " and request method.\",\n    \"data\": {\n        \"status\": 404\n    }\n}");
        fixed(Http.as(null).get("/wp-json/qp-test/v1/none?_envelope").then())
                .statusCode(200)
                // REST verification list item 15
                .body(equalTo("{\"body\":" + DispatchDbTest.NO_ROUTE + ",\"status\":404,\"headers\":[]}"));
        // REST verification list item 15: the totals as JSON integers, in the order set, then Allow, and not sent as
        // HTTP headers
        fixed(Http.as(null).get("/wp-json/qp-test/v1/items?_envelope=1").then())
                .statusCode(200)
                .header("X-WP-Total", nullValue())
                .header("X-WP-TotalPages", nullValue())
                .header("Allow", nullValue())
                .body(equalTo("{\"body\":" + TestRoutes.ITEMS
                        + ",\"status\":200,\"headers\":{\"X-WP-Total\":2,\"X-WP-TotalPages\":1,\"Allow\":\"GET\"}}"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("X-WP-Total", "2")
                .header("X-WP-TotalPages", "1");
        // _envelope and _pretty come from the query string only
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .formParam("_envelope", "1")
                .formParam("_pretty", "1")
                .formParam("_jsonp", "cb")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(200)
                .body(equalTo("{\"a\":null}"));
        for (String callback : List.of("cb", "a-b")) {
            // JSONP is never enabled (README D5: WordPress serves _jsonp=cb). The refusal carries the headers
            // WordPress sends with its refusal when JSONP is disabled (the parity scenario d15 runs WordPress with
            // JSONP enabled): Content-Type application/json, X-Robots-Tag, the API Link, nosniff, and
            // no Access-Control-* lines, no Vary, no no-cache; it comes before authentication (an invalid token does
            // not change it)
            for (String bearer : new String[] {null, "invalid", "test-" + editor.value()}) {
                jsonpRefusal(Http.as(bearer).get("/wp-json/qp-test/v1/items?_jsonp=" + callback))
                        .body(equalTo(JSONP_DISABLED));
            }
        }
        // also before matching and with any value, and HEAD drops the body
        jsonpRefusal(Http.as(null).get("/wp-json/qp-test/v1/none?_jsonp[]=&_envelope&_pretty"))
                .body(equalTo(JSONP_DISABLED));
        // a callback PHP counts as false ("" or "0") asks for no JSONP: the request is served
        for (String empty : List.of("_jsonp", "_jsonp=", "_jsonp=0")) {
            fixed(Http.as(null).get("/wp-json/qp-test/v1/items?" + empty).then())
                    .statusCode(200)
                    .body(equalTo(TestRoutes.ITEMS));
        }
        jsonpRefusal(Http.as("invalid").head("/wp-json/qp-test/v1/items?_jsonp=cb"))
                .body(emptyString());
        fixed(Http.as(null).head("/wp-json/qp-test/v1/items").then())
                .statusCode(200)
                .header("X-WP-Total", "2")
                .body(emptyString());
        // HEAD asked for through the override has no body either
        Http.as(null)
                .get("/wp-json/qp-test/v1/items?_method=HEAD")
                .then()
                .statusCode(200)
                .body(emptyString());
        Http.as(null).head("/wp-json/qp-test/v1/none").then().statusCode(404).body(emptyString());
    }

    private static ValidatableResponse jsonpRefusal(Response response) {
        return response.then()
                .statusCode(400)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Robots-Tag", "noindex")
                .header("X-Content-Type-Options", "nosniff")
                .header("Link", ROOT_LINK)
                .header("Access-Control-Expose-Headers", nullValue())
                .header("Access-Control-Allow-Headers", nullValue())
                .header("Vary", nullValue())
                .header("Expires", nullValue())
                .header("WWW-Authenticate", nullValue());
    }

    @Test
    void aRestRouteRefusalKeepsWordPresssPreServerAnswer() {
        // REST verification list item 2: the request dies before the REST server, so none of its stages apply, except
        // that HEAD has no body
        for (String url : List.of("/?rest_route[]=x&_jsonp=cb&_envelope&_pretty", "/?rest_route[]=x")) {
            Http.as("invalid")
                    .header("Origin", "https://x.test")
                    .get(url)
                    .then()
                    .statusCode(400)
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .header("Expires", EXPIRES)
                    .header("Link", nullValue())
                    .header("X-Robots-Tag", nullValue())
                    .header("Access-Control-Expose-Headers", nullValue())
                    .header("Vary", nullValue())
                    .body(containsString("The REST route parameter must be a string."));
        }
        for (String bearer : new String[] {null, "invalid"}) {
            Http.as(bearer)
                    .head("/?rest_route[]=x")
                    .then()
                    .statusCode(400)
                    .header("Expires", EXPIRES)
                    .header("Link", nullValue())
                    .body(emptyString());
        }
    }
}
