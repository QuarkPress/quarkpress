package org.quarkpress.restwp.deployment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Only configured origins are answered (README D4: WordPress reflects any origin with credentials), with the wider
 * header lists; every REST answer carries them, the token error and the JSON errors included.
 */
class CorsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.with(CorsDbTest.class, TestRoutes.class)
            .overrideConfigKey("quarkpress.rest-wp-v2.cors.origins", "https://admin.test, null");

    static final String WIDE_EXPOSED = "X-WP-Total, X-WP-TotalPages, Link, Allow, X-WP-Upload-Attachment-ID";
    static final String WIDE_ALLOWED =
            "Authorization, X-WP-Nonce, Content-Disposition, Content-MD5, Content-Type, X-HTTP-Method-Override";

    @Test
    void aConfiguredOriginIsReflectedWithTheWiderLists() {
        allowed(
                Http.as(null)
                        .header("Origin", "https://admin.test")
                        .options("/wp-json/qp-test/v1/items")
                        .then(),
                "https://admin.test");
        Http.as(null)
                .header("Origin", "https://evil.test")
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Credentials", nullValue())
                .header("Vary", "Origin")
                .header("Access-Control-Expose-Headers", "X-WP-Total, X-WP-TotalPages, Link");
    }

    @Test
    void everyKindOfAnswerCarriesTheConfiguredOrigin() {
        // a logged-out read, an error, the token error, its HEAD, and the literal origin "null" (kept, as WordPress)
        allowed(
                Http.as(null)
                        .header("Origin", "https://admin.test")
                        .get("/wp-json/qp-test/v1/none")
                        .then()
                        .statusCode(404),
                "https://admin.test");
        allowed(
                Http.as("invalid")
                        .header("Origin", "https://admin.test")
                        .post("/wp-json/qp-test/v1/json")
                        .then()
                        .statusCode(401)
                        .body("code", equalTo("rest_invalid_token")),
                "https://admin.test");
        allowed(
                Http.as("invalid")
                        .header("Origin", "https://admin.test")
                        .head("/wp-json/qp-test/v1/items")
                        .then()
                        .statusCode(401),
                "https://admin.test");
        allowed(
                Http.as(null)
                        .header("Origin", "null")
                        .contentType("application/json")
                        .body("{bad")
                        .post("/wp-json/qp-test/v1/json")
                        .then()
                        .statusCode(400),
                "null");
        // ASCII case is ignored and the request's own spelling is echoed
        allowed(
                Http.as(null)
                        .header("Origin", "https://ADMIN.test")
                        .get("/wp-json/qp-test/v1/items")
                        .then(),
                "https://ADMIN.test");
    }

    @Test
    void lookAlikesListsAndTheJsonpRefusalGetNoOrigin() {
        for (String origin : new String[] {
            "https://admin.test.evil.test",
            "https://admin.test, https://evil.test",
            "https://admin.test/",
            "http://admin.test"
        }) {
            Http.as(null)
                    .header("Origin", origin)
                    .get("/wp-json/qp-test/v1/items")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", nullValue())
                    .header("Access-Control-Expose-Headers", "X-WP-Total, X-WP-TotalPages, Link");
        }
        // two Origin headers are read joined, as PHP holds them: no single configured origin
        Http.as(null)
                .header("Origin", "https://admin.test")
                .header("Origin", "null")
                .get("/wp-json/qp-test/v1/items")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
        Http.as(null)
                .header("Origin", "https://admin.test")
                .get("/wp-json/qp-test/v1/items?_jsonp=cb")
                .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Vary", nullValue());
        // a request that dies before the REST server gets none either (Part D 2)
        Http.as(null)
                .header("Origin", "https://admin.test")
                .get("/?rest_route[]=x")
                .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", nullValue())
                .body(containsString("The REST route parameter must be a string."));
    }

    private static ValidatableResponse allowed(ValidatableResponse response, String origin) {
        return response.header("Access-Control-Allow-Origin", origin)
                .header("Access-Control-Allow-Methods", "OPTIONS, GET, POST, PUT, PATCH, DELETE")
                .header("Access-Control-Allow-Credentials", "true")
                .header("Vary", "Origin")
                .header("Access-Control-Expose-Headers", WIDE_EXPOSED)
                .header("Access-Control-Allow-Headers", WIDE_ALLOWED)
                .header("X-Robots-Tag", "noindex");
    }
}
