package org.quarkpress.it.content;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ContentDbTest {

    @Test
    void theMainSiteIsCurrent() {
        given().when()
                .get("/it/site")
                .then()
                .statusCode(200)
                .body("id", is(1))
                .body("domain", is("localhost"))
                .body("path", is("/"))
                .body("name", is("QuarkPress"))
                .body("status", is("ACTIVE"));
    }

    @Test
    void optionsRoundTrip() {
        String name = "it-" + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("{\"a\":[1,2]}")
                .when()
                .put("/it/options/" + name)
                .then()
                .statusCode(200)
                .body("changed", is(true));
        given().contentType(ContentType.JSON)
                .body("{\"a\":[1,2]}")
                .when()
                .put("/it/options/" + name)
                .then()
                .statusCode(200)
                .body("changed", is(false));
        given().when().get("/it/options/" + name).then().statusCode(200).body("a", contains(1, 2));
        given().when().delete("/it/options/" + name).then().statusCode(200).body("deleted", is(true));
        given().when().get("/it/options/" + name).then().statusCode(404);
    }

    @Test
    void theTimeZoneFollowsTheOptions() {
        try {
            given().contentType(ContentType.JSON)
                    .body("\"America/Toronto\"")
                    .when()
                    .put("/it/options/timezone_string")
                    .then()
                    .statusCode(200);
            given().when().get("/it/site").then().statusCode(200).body("zone", is("America/Toronto"));
        } finally {
            given().when().delete("/it/options/timezone_string").then().statusCode(200);
        }
    }

    @Test
    void sitesAreIsolated() {
        long a = given().when()
                .post("/it/sites/a")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        long b = given().when()
                .post("/it/sites/b")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        given().when()
                .get("/it/isolation/" + a + "/" + b)
                .then()
                .statusCode(200)
                .body(equalTo("isolated"));
    }

    @Test
    void applicationsMayNotOpenTheNetworkScope() {
        given().when().get("/it/network-scope").then().statusCode(200).body(equalTo("refused"));
    }

    @Test
    void codeWithoutARequestContextFailsClosed() {
        given().when().get("/it/no-request-context").then().statusCode(200).body(equalTo("failed-closed"));
    }
}
