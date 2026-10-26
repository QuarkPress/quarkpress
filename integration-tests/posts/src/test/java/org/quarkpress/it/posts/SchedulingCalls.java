package org.quarkpress.it.posts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The HTTP calls the scheduling tests share, with the Keycloak client. In an integration test, Quarkus hands the
 * Dev Services context to the test's fields of this type, which pass it on to the Keycloak client.
 */
class SchedulingCalls implements DevServicesContext.ContextAware {

    private static final Duration STEP = Duration.ofMillis(500);

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        keycloak.setIntegrationTestContext(context);
    }

    RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    void role(String user, String role) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(user), "role", role))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    JsonPath create(String user, Map<String, Object> body) {
        return create(as(user), body);
    }

    /** Creates a post with an already authenticated specification: no token is fetched between call and post. */
    JsonPath create(RequestSpecification request, Map<String, Object> body) {
        return request.contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    String status(long id) {
        return as("alice")
                .get("/it/posts/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("status");
    }

    /** The status of post {@code id} as the node listening on {@code port} reads it. */
    String statusOn(int port, long id) {
        return as("alice")
                .port(port)
                .get("/it/posts/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("status");
    }

    void due(long id) {
        as("alice").post("/it/scheduling/due/" + id).then().statusCode(200);
    }

    void feature(boolean enabled) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("enabled", enabled))
                .put("/it/scheduling/feature")
                .then()
                .statusCode(200);
    }

    List<Long> published() {
        return publishedOn(RestAssured.port);
    }

    /** The posts the node listening on {@code port} published. */
    List<Long> publishedOn(int port) {
        return given().port(port)
                .header("Host", "localhost")
                .get("/it/scheduling/published")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("posts", Long.class);
    }

    /** The jobs' status of the node listening on {@code port}, as its JSON text. */
    String statusOn(int port) {
        return given().port(port)
                .header("Host", "localhost")
                .get("/it/scheduling/status")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    /** When the last poll of the node listening on {@code port} started; empty while it has not polled yet. */
    Optional<Instant> lastPollStartedOn(int port) {
        String started = given().port(port)
                .header("Host", "localhost")
                .get("/it/scheduling/status")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("lastPollStarted");
        return started == null ? Optional.empty() : Optional.of(Instant.parse(started));
    }

    /** Schedules a post for 2090 as alice and returns its id. */
    long scheduleFarAhead() {
        JsonPath created =
                create("alice", Map.of("title", token(), "status", "publish", "dateGmt", "2090-01-01T00:00:00Z"));
        assertThat(created.getString("status")).isEqualTo("future");
        return created.getLong("id");
    }

    /** Waits, in half-second steps, until {@code condition} holds; fails once {@code limit} has elapsed. */
    static void await(Duration limit, BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail("timed out waiting for " + what);
            }
            pause();
        }
    }

    /** Asserts, in half-second steps, that {@code condition} stays true until {@code duration} has elapsed. */
    static void holds(Duration duration, BooleanSupplier condition) {
        Instant end = Instant.now().plus(duration);
        do {
            assertThat(condition.getAsBoolean()).isTrue();
            pause();
        } while (Instant.now().isBefore(end));
    }

    private static void pause() {
        try {
            Thread.sleep(STEP.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }
}
