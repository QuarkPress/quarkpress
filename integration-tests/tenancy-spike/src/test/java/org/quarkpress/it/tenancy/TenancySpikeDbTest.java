package org.quarkpress.it.tenancy;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusTest
class TenancySpikeDbTest {

    static Stream<String> criteria() {
        return SpikeScenarios.CRITERIA.stream();
    }

    @ParameterizedTest
    @MethodSource("criteria")
    void criterionHolds(String criterion) {
        given().when().get("/spike/" + criterion).then().statusCode(200).body(equalTo("ok"));
    }

    @Test
    void anUnguardedBulkUpdateChangesOnlyTheBoundSitesRow() {
        String outcome = given().when()
                .get("/spike/observe/unguarded-bulk-update")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        System.out.println("tenancy-spike observation [" + System.getenv("QUARKPRESS_TEST_DB")
                + "]: an unguarded bulk update under site 1: " + outcome);
        assertThat(outcome).isEqualTo("rows=1 site1=changed site2=unchanged");
    }
}
