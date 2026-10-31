package org.quarkpress.restwp.deployment;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The REST API trusts an identity only when the request carried {@code Authorization: Bearer ...}. A request that
 * another mechanism authenticated (here {@link TestBearer}'s session cookie, as an OIDC web application's session
 * cookie would) is served as anonymous: reads answer as to anyone, and writes are refused as to an anonymous caller,
 * through every entry form, so another site's page cannot make a browser write with the user's rights.
 */
class SessionIdentityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(SessionIdentityDbTest.class);

    long administrator;
    AgroalDataSource data;
    String u;

    @BeforeEach
    void members() {
        data = Jdbc.dataSource();
        administrator = TestData.memberOfMain(data, "administrator").value();
        u = UUID.randomUUID().toString().substring(0, 8);
    }

    /** A request authenticated by the session cookie of {@code user}, with no {@code Authorization} header. */
    static RequestSpecification session(long user) {
        return Http.as(null).cookie(TestBearer.SESSION, "test-" + user);
    }

    @Test
    void aSessionIdentityIsAnonymousToTheRestApi() {
        session(administrator)
                .get("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_not_logged_in"))
                .body("message", equalTo("You are not currently logged in."))
                // an anonymous answer: not marked uncacheable as a logged-in caller's is
                .header("Cache-Control", nullValue());
        Http.as("test-" + administrator)
                .get("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200)
                .body("id", equalTo((int) administrator));
        // the session's user may manage categories, but the request is anonymous: the edit context is refused
        session(administrator)
                .get("/wp-json/wp/v2/categories?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"))
                .body("message", equalTo("Sorry, you are not allowed to edit terms in this taxonomy."));
    }

    @Test
    void aSessionIdentityCannotWriteThroughAnyEntryForm() {
        session(administrator)
                .contentType("application/json")
                .body("{\"name\":\"Session " + u + "\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create terms in this taxonomy."));
        long category = Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"name\":\"Kept " + u + "\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
        String deleted = "Sorry, you are not allowed to delete this term.";
        session(administrator)
                .delete("/wp-json/wp/v2/categories/" + category + "?force=true")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo(deleted));
        // the cross-site forms: a link (GET with _method), and simple form POSTs carrying rest_route, one changing the
        // term and one deleting it (_method is read from the query only)
        session(administrator)
                .get("/wp-json/wp/v2/categories/" + category + "?_method=DELETE&force=true")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo(deleted));
        session(administrator)
                .contentType("application/x-www-form-urlencoded")
                .formParam("rest_route", "/wp/v2/categories/" + category)
                .formParam("name", "Changed " + u)
                .post("/")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_update"))
                .body("message", equalTo("Sorry, you are not allowed to edit this term."));
        session(administrator)
                .contentType("application/x-www-form-urlencoded")
                .formParam("rest_route", "/wp/v2/categories/" + category)
                .formParam("force", "true")
                .post("/?_method=DELETE")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo(deleted));
        Http.as(null)
                .get("/wp-json/wp/v2/categories/" + category)
                .then()
                .statusCode(200)
                .body("name", equalTo("Kept " + u));
        // the same user with a Bearer token deletes it
        Http.as("test-" + administrator)
                .delete("/wp-json/wp/v2/categories/" + category + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));
    }
}
