package org.quarkpress.it.content;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class IsolationDbTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "caches-off",
                "collections-filtered",
                "loaded-site-guarded",
                "propagated-tasks-keep-their-site",
                "unbound-writes-refused"
            })
    void siteIsolationHolds(String criterion) {
        given().when()
                .get("/it/isolation-check/" + criterion)
                .then()
                .statusCode(200)
                .body(equalTo("ok"));
    }
}
