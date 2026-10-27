package org.quarkpress.devui.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.quarkpress.devui.deployment.DevUiClient.find;
import static org.quarkpress.devui.deployment.DevUiClient.texts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.value.registry.ValueRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The scheduler page's data and the dev users, read from a dev-mode application that {@link DevUiSeed} fills on every
 * start (each test method gets a new application and an empty database); the security module's dev seeding gives the
 * realm's users their profiles and roles. The scheduler is off, so no poll has run.
 */
class SchedulerAndUsersDbTest {

    @RegisterExtension
    static final QuarkusDevModeTest app = DevUiApp.dev(DevUiSeed.class);

    final DevUiClient devUi = new DevUiClient();

    @BeforeEach
    void connect(ValueRegistry registry) {
        devUi.connect(registry);
    }

    @Test
    void schedulerListsTheNextScheduledPostsOfEverySiteAndThisNodesStatus() throws Exception {
        long second = find(devUi.call("sites"), "domain", "second.example.test")
                .get("id")
                .asLong();
        JsonNode scheduler = devUi.call("scheduler");
        JsonNode upcoming = scheduler.get("upcoming");
        assertThat(texts(upcoming, "title")).containsExactly("Elsewhere", "Later");
        assertThat(upcoming.get(0).get("site").asLong()).isEqualTo(second);
        assertThat(upcoming.get(0).get("publishAt").asText()).isEqualTo("2089-12-31T00:00:00Z");
        assertThat(upcoming.get(1).get("site").asLong()).isEqualTo(1);
        assertThat(upcoming.get(1).get("type").asText()).isEqualTo("post");
        assertThat(upcoming.get(1).get("publishAt").asText()).isEqualTo("2090-01-01T00:00:00Z");
        assertThat(scheduler.get("publishedSinceStart").asLong()).isZero();
        assertThat(scheduler.has("lastPollStarted")).isFalse();
        assertThat(scheduler.get("lastBatch")).isEmpty();
        assertThat(scheduler.has("lastFailure")).isFalse();
    }

    @Test
    void devUsersJoinTheRealmWithTheirProfilesAndMemberships() throws Exception {
        JsonNode users = devUi.call("devUsers");
        assertThat(texts(users, "username")).containsExactly("alice", "bob", "carol", "dave", "erin");
        JsonNode bob = find(users, "username", "bob");
        assertThat(bob.get("subject").asText()).isEqualTo("b0b00000-0000-4000-8000-000000000002");
        assertThat(texts(bob.get("realmRoles"))).containsExactly("administrator");
        assertThat(bob.get("hasProfile").asBoolean()).isTrue();
        assertThat(bob.get("networkAdmin").asBoolean()).isFalse();
        assertThat(bob.get("siteRoles")).hasSize(1);
        assertThat(bob.get("siteRoles").get(0).get("site").asLong()).isEqualTo(1);
        assertThat(texts(bob.get("siteRoles").get(0).get("roles"))).containsExactly("editor");
        JsonNode alice = find(users, "username", "alice");
        assertThat(alice.get("networkAdmin").asBoolean()).isTrue();
        assertThat(alice.get("siteRoles")).isEmpty();
    }

    @Test
    void theTokenLinkIsOmittedWithoutKeycloakDevServices() {
        given().get("/q/dev-ui/" + DevUiClient.NAMESPACE + "-data.js")
                .then()
                .statusCode(200)
                .body(containsString("export const modules ="))
                .body(not(containsString("export const tokenPage")));
    }
}
