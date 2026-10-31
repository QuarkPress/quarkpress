package org.quarkpress.it.host;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HostCoexistenceDbTest {

    private static String vendor() {
        String db = System.getenv("QUARKPRESS_TEST_DB");
        return db == null || db.isBlank() ? "postgresql" : db;
    }

    @Test
    void eachSideMigratesOnlyItsOwnScripts() {
        // Only type = 'SQL' rows: a baseline row is not a script. Flyway records the script with its location.
        given().when()
                .get("/host/check")
                .then()
                .statusCode(200)
                .body("hostScripts", contains("db/migration/V1__erp_invoice.sql"))
                .body(
                        "quarkpressScripts",
                        contains(
                                "db/quarkpress/" + vendor() + "/V1_001__persistence_create_tables.sql",
                                "db/quarkpress/common/V1_002__persistence_main_site.sql",
                                "db/quarkpress/common/V1_003__persistence_network_defaults.sql",
                                "db/quarkpress/" + vendor() + "/V1_004__persistence_network_lock.sql",
                                "db/quarkpress/" + vendor() + "/V1_005__persistence_site_defaults.sql",
                                "db/quarkpress/" + vendor() + "/V1_006__persistence_more_site_defaults.sql"));
    }

    @Test
    void eachFlywayReadsOnlyItsOwnLocations() {
        // quarkus-flyway activates Flyway for the host's default datasource too, on the default location.
        given().when()
                .get("/host/check")
                .then()
                .statusCode(200)
                .body("hostFlywayLocations", contains("classpath:db/migration"))
                .body(
                        "quarkpressFlywayLocations",
                        contains("classpath:db/quarkpress/common", "classpath:db/quarkpress/" + vendor()));
    }

    @Test
    void thePersistenceUnitsDoNotShareEntities() {
        given().when()
                .get("/host/check")
                .then()
                .statusCode(200)
                .body("defaultUnitEntities", contains("Invoice"))
                .body("quarkpressUnitEntities", hasItems("SiteEntity", "OptionEntity", "NetworkOptionEntity"))
                .body("quarkpressUnitEntities", not(hasItem("Invoice")));
    }

    @Test
    void bothDatasourcesUseOneDatabase() {
        given().when().get("/host/check").then().statusCode(200).body("sameDatabase", is(true));
    }

    @Test
    void hostEntitiesAndQuarkPressOptionsWorkSideBySide() {
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        given().when().post("/host/invoices/" + id + "/INV-1").then().statusCode(204);
        given().when().get("/host/invoices/" + id).then().statusCode(200).body(equalTo("INV-1"));
        String name = "host-" + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("\"from the host\"")
                .when()
                .put("/host/qp-options/" + name)
                .then()
                .statusCode(204);
        given().when().get("/host/qp-options/" + name).then().statusCode(200).body(equalTo("from the host"));
    }

    @Test
    void hostJsonUsesTheHostMapperAndQuarkPressJsonKeepsItsOwnBehaviour() {
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        given().when().post("/host/invoices/" + id + "/INV-2").then().statusCode(204);
        given().when().put("/host/invoices/" + id + "/details/Ada/3").then().statusCode(204);
        given().when()
                .get("/host/invoices/" + id + "/details")
                .then()
                .statusCode(200)
                .body(equalTo("Ada/3"));
        // The host's ObjectMapper customization (snake_case) wrote the host's column ...
        given().when()
                .get("/host/invoices/" + id + "/details-raw")
                .then()
                .statusCode(200)
                .body(containsString("customer_name"))
                .body(not(containsString("customerName")));
        // ... and left QuarkPress's own mapper alone: names are kept as written and so is the key order.
        String name = "host-json-" + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("{\"zebra\":1,\"customerName\":\"Ada\",\"alpha\":[3,2,1],\"mid\":{\"y\":1,\"x\":2}}")
                .when()
                .put("/host/qp-options/" + name)
                .then()
                .statusCode(204);
        given().when()
                .get("/host/qp-options/" + name + "/json")
                .then()
                .statusCode(200)
                .body(equalTo("{\"zebra\":1,\"customerName\":\"Ada\",\"alpha\":[3,2,1],\"mid\":{\"y\":1,\"x\":2}}"));
    }
}
