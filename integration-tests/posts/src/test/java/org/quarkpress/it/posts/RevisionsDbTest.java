package org.quarkpress.it.posts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RevisionsDbTest {

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    private long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    private void role(String user, String role) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(user), "role", role))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    private long post(String user, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private void update(String user, long id, Map<String, Object> body) {
        as(user).contentType(ContentType.JSON)
                .body(body)
                .put("/it/posts/" + id)
                .then()
                .statusCode(200);
    }

    private JsonPath revisions(String user, long id) {
        return as(user).get("/it/posts/" + id + "/revisions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private JsonPath autosave(String user, long id, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts/" + id + "/autosaves")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void updatesSaveRevisionsThatEditorsListAndContributorsCannot() {
        role("bob", "editor");
        role("dave", "contributor");
        long id = post("bob", Map.of("title", "v1", "content", "one", "status", "publish"));
        assertThat(revisions("bob", id).getList("id")).isEmpty();
        update("bob", id, Map.of("title", "v2"));
        update("alice", id, Map.of("title", "v3"));
        update("alice", id, Map.of("title", "v3"));
        JsonPath listed = revisions("bob", id);
        assertThat(listed.getList("title", String.class)).containsExactly("v3", "v2");
        assertThat(listed.getList("autosave", Boolean.class)).containsExactly(false, false);
        assertThat(listed.getLong("[0].author")).isEqualTo(userId("alice"));
        as("dave").get("/it/posts/" + id + "/revisions").then().statusCode(403);
        as(null).get("/it/posts/" + id + "/revisions").then().statusCode(401);
        JsonPath count = as("bob")
                .get("/it/posts/" + id + "/revisions/count")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(count.getLong("count")).isEqualTo(2);
        assertThat(count.getLong("latest")).isEqualTo(listed.getLong("[0].id"));
    }

    @Test
    void autosavesStoreOthersPostsAndUpdateOwnDrafts() {
        role("bob", "editor");
        role("carol", "author");
        role("dave", "contributor");
        long published = post("bob", Map.of("title", "T", "content", "Body", "status", "publish"));
        JsonPath stored = autosave("alice", published, Map.of("content", "Body 2"));
        assertThat(stored.getBoolean("autosave")).isTrue();
        assertThat(stored.getString("content")).isEqualTo("Body 2");
        assertThat(stored.getLong("author")).isEqualTo(userId("alice"));
        JsonPath again = autosave("alice", published, Map.of("content", "Body 2"));
        assertThat(again.getLong("id")).isEqualTo(stored.getLong("id"));
        assertThat(revisions("bob", published).getList("autosave", Boolean.class))
                .containsExactly(true);
        assertThat(as("bob")
                        .get("/it/posts/" + published)
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getString("content"))
                .isEqualTo("Body");
        long draft = post("carol", Map.of("title", "D", "content", "x", "status", "draft"));
        JsonPath updated = autosave("carol", draft, Map.of("content", "xy"));
        assertThat(updated.getString("post.content")).isEqualTo("xy");
        assertThat(revisions("bob", draft).getList("id")).isEmpty();
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("content", "no"))
                .post("/it/posts/" + published + "/autosaves")
                .then()
                .statusCode(403);
        as("dave")
                .contentType(ContentType.JSON)
                .body(Map.of("content", "no"))
                .post("/it/posts/" + published + "/autosaves")
                .then()
                .statusCode(403);
        JsonPath missing = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("content", "no"))
                .post("/it/posts/999999999/autosaves")
                .then()
                .statusCode(404)
                .extract()
                .jsonPath();
        assertThat(missing.getString("code")).isEqualTo("invalid_post");
    }

    @Test
    void restoresBringTextBackAndDeletesAreRefusedForEveryone() {
        role("bob", "editor");
        long id = post("bob", Map.of("title", "v1", "content", "one", "status", "publish"));
        update("bob", id, Map.of("content", "two"));
        update("bob", id, Map.of("content", "three"));
        JsonPath listed = revisions("bob", id);
        long older = listed.getLong("[1].id");
        JsonPath restored = as("bob")
                .post("/it/revisions/" + older + "/restore")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(restored.getString("content")).isEqualTo("two");
        assertThat(revisions("bob", id).getList("content", String.class)).containsExactly("two", "three", "two");
        as("alice").delete("/it/revisions/" + older).then().statusCode(403);
        as("bob").delete("/it/revisions/" + older).then().statusCode(403);
        as(null).delete("/it/revisions/" + older).then().statusCode(401);
        assertThat(revisions("bob", id).getList("id")).hasSize(3);
        JsonPath refused = as("bob")
                .post("/it/revisions/999999999/restore")
                .then()
                .statusCode(400)
                .extract()
                .jsonPath();
        assertThat(refused.getString("code")).isEqualTo("rest_post_invalid_id");
    }
}
