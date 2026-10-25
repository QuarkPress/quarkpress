package org.quarkpress.it.posts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.it.posts.SchedulingCalls.await;
import static org.quarkpress.it.posts.SchedulingCalls.holds;
import static org.quarkpress.it.posts.SchedulingCalls.token;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SchedulingDbTest {

    SchedulingCalls calls = new SchedulingCalls();

    @Test
    void aPostScheduledAMinuteAheadIsPublishedByThePoll() {
        RequestSpecification alice = calls.as("alice"); // the Keycloak token is fetched here, once, before the lead
        String gmt =
                Instant.now().plusSeconds(90).truncatedTo(ChronoUnit.SECONDS).toString();
        JsonPath created = calls.create(alice, Map.of("title", token(), "status", "publish", "dateGmt", gmt));
        assertThat(created.getString("status")).isEqualTo("future");
        long id = created.getLong("id");
        await(Duration.ofSeconds(150), () -> calls.status(id).equals("publish"), "post " + id + " to be published");
        assertThat(calls.published()).containsOnlyOnce(id);
        JsonPath status = calls.as("alice")
                .get("/it/scheduling/status")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(status.getString("lastPollEnded")).isNotNull();
    }

    @Test
    void aSiteWithSchedulingOffHoldsItsPostsBackUntilItIsOnAgain() {
        calls.feature(false);
        try {
            long id = calls.scheduleFarAhead();
            calls.due(id);
            holds(Duration.ofSeconds(4), () -> calls.status(id).equals("future"));
            calls.feature(true);
            await(Duration.ofSeconds(30), () -> calls.status(id).equals("publish"), "post " + id + " after the switch");
        } finally {
            calls.feature(true);
        }
    }

    @Test
    void housekeepingPurgesOldTrashAndOldAutoDrafts() {
        long old = calls.create("alice", Map.of("title", token(), "status", "publish"))
                .getLong("id");
        calls.as("alice").post("/it/posts/" + old + "/trash").then().statusCode(200);
        calls.as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("seconds", 1000000000L))
                .post("/it/scheduling/trash-time/" + old)
                .then()
                .statusCode(200);
        long fresh = calls.create("alice", Map.of("title", token(), "status", "publish"))
                .getLong("id");
        calls.as("alice").post("/it/posts/" + fresh + "/trash").then().statusCode(200);
        long stale = calls.create("alice", Map.of("title", token(), "status", "auto-draft"))
                .getLong("id");
        calls.as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("at", "2020-01-01T00:00:00Z"))
                .post("/it/scheduling/modified/" + stale)
                .then()
                .statusCode(200);
        JsonPath result = calls.as("alice")
                .post("/it/scheduling/housekeeping")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(result.getInt("trash")).isGreaterThanOrEqualTo(1);
        assertThat(result.getInt("autoDrafts")).isGreaterThanOrEqualTo(1);
        calls.as("alice").get("/it/posts/" + old).then().statusCode(404);
        calls.as("alice").get("/it/posts/" + stale).then().statusCode(404);
        assertThat(calls.status(fresh)).isEqualTo("trash");
        JsonPath status = calls.as("alice")
                .get("/it/scheduling/status")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(status.getString("lastHousekeeping")).isNotNull();
    }

    @Test
    void theFixturesAndTheSwitchNeedAnAdministrator() {
        calls.role("bob", "editor");
        calls.as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("enabled", false))
                .put("/it/scheduling/feature")
                .then()
                .statusCode(403);
        long id = calls.create("alice", Map.of("title", token())).getLong("id");
        calls.as("bob").post("/it/scheduling/due/" + id).then().statusCode(403);
        calls.as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("seconds", 1L))
                .post("/it/scheduling/trash-time/" + id)
                .then()
                .statusCode(403);
        calls.as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("at", "2020-01-01T00:00:00Z"))
                .post("/it/scheduling/modified/" + id)
                .then()
                .statusCode(403);
        assertThat(calls.status(id)).isEqualTo("draft");
        calls.as(null).post("/it/scheduling/housekeeping").then().statusCode(401);
    }
}
