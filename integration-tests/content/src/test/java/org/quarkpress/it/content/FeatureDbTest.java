package org.quarkpress.it.content;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FeatureDbTest {

    @BeforeEach
    @AfterEach
    void reset() {
        given().when().delete("/it/features/demo").then().statusCode(204);
        given().when().delete("/it/features/demo-on").then().statusCode(204);
        given().when().delete("/it/features/network/demo").then().statusCode(204);
        given().when().delete("/it/features/network/demo-on").then().statusCode(204);
    }

    @Test
    void defaultsComeFromTheDeclaringModule() {
        given().when()
                .get("/it/features/demo")
                .then()
                .statusCode(200)
                .body("enabled", is(false))
                .body("source", is("MODULE"));
        given().when()
                .get("/it/features/demo-on")
                .then()
                .statusCode(200)
                .body("enabled", is(true))
                .body("source", is("MODULE"));
    }

    @Test
    void aDisabledFeatureHidesTheResourceSkipsObserversPassesFiltersAndRefusesServices() {
        given().when().get("/it/demo").then().statusCode(404).body(emptyString());
        given().when().get("/it/demo-probe/observer").then().statusCode(200).body(equalTo("0"));
        given().queryParam("text", "x")
                .when()
                .get("/it/demo-probe/filter")
                .then()
                .statusCode(200)
                .body(equalTo("x"));
        given().when().get("/it/demo-probe/service").then().statusCode(200).body(equalTo("disabled"));
    }

    @Test
    void anUncaughtDisabledServiceCallIsLeftToTheApplicationsMappers() {
        // No mapper in this application: Quarkus answers 500 for the unmapped SiteFeatureDisabledException.
        given().when().get("/it/demo-probe/service-uncaught").then().statusCode(500);
        given().when().put("/it/features/demo/true").then().statusCode(204);
        given().when()
                .get("/it/demo-probe/service-uncaught")
                .then()
                .statusCode(200)
                .body(equalTo("hello"));
    }

    @Test
    void aFeatureOnByDefaultGatesUntilTheSiteOrTheNetworkTurnsItOff() {
        given().when().get("/it/demo-on").then().statusCode(200).body(equalTo("demo-on"));
        given().when().put("/it/features/network/demo-on/false").then().statusCode(204);
        given().when().get("/it/demo-on").then().statusCode(404).body(emptyString());
        given().when().put("/it/features/demo-on/true").then().statusCode(204);
        given().when().get("/it/demo-on").then().statusCode(200).body(equalTo("demo-on"));
        given().when().put("/it/features/demo-on/false").then().statusCode(204);
        given().when().get("/it/demo-on").then().statusCode(404).body(emptyString());
        given().when().delete("/it/features/demo-on").then().statusCode(204);
        given().when().delete("/it/features/network/demo-on").then().statusCode(204);
        given().when().get("/it/demo-on").then().statusCode(200).body(equalTo("demo-on"));
    }

    @Test
    void anEnabledFeatureRunsEverything() {
        given().when().put("/it/features/demo/true").then().statusCode(204);
        given().when().get("/it/demo").then().statusCode(200).body(equalTo("demo"));
        given().when().get("/it/demo-probe/observer").then().statusCode(200).body(equalTo("1"));
        given().queryParam("text", "x")
                .when()
                .get("/it/demo-probe/filter")
                .then()
                .statusCode(200)
                .body(equalTo("x+demo"));
        given().when().get("/it/demo-probe/service").then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void switchingAFeatureOffAgainClosesTheGates() {
        given().when().put("/it/features/demo/true").then().statusCode(204);
        given().when().get("/it/demo").then().statusCode(200).body(equalTo("demo"));
        given().when().put("/it/features/demo/false").then().statusCode(204);
        given().when().get("/it/demo").then().statusCode(404).body(emptyString());
        given().when().get("/it/demo-probe/service").then().statusCode(200).body(equalTo("disabled"));
    }

    @Test
    void networkDefaultsApplyUntilTheSiteDecides() {
        given().when().put("/it/features/network/demo/true").then().statusCode(204);
        given().when()
                .get("/it/features/demo")
                .then()
                .statusCode(200)
                .body("enabled", is(true))
                .body("source", is("NETWORK"));
        given().when().get("/it/demo").then().statusCode(200).body(equalTo("demo"));
        given().when().put("/it/features/demo/false").then().statusCode(204);
        given().when()
                .get("/it/features/demo")
                .then()
                .statusCode(200)
                .body("enabled", is(false))
                .body("source", is("SITE"));
        given().when().get("/it/demo").then().statusCode(404).body(emptyString());
    }
}
