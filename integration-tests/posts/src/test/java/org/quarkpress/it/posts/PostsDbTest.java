package org.quarkpress.it.posts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PostsDbTest {

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

    private static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private int createStatus(String user, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts")
                .statusCode();
    }

    private JsonPath create(String user, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private void refused(RequestSpecification request, String method, String path, int status) {
        var response = request.request(method, path).then().statusCode(status);
        if (status == 401) {
            response.header("WWW-Authenticate", "Bearer");
        }
        response.body(equalTo(""));
    }

    @Test
    void administratorsKeepScriptsAuthorsLoseThem() {
        role("bob", "author");
        String text = "<script>x()</script>ok";
        JsonPath byAlice = create("alice", Map.of("title", token(), "content", text, "status", "publish"));
        assertThat(byAlice.getString("content")).isEqualTo(text);
        JsonPath byBob = create("bob", Map.of("title", token(), "content", text, "status", "publish"));
        assertThat(byBob.getString("content")).doesNotContain("<script").endsWith("ok");
        assertThat(byBob.getLong("author")).isEqualTo(userId("bob"));
    }

    @Test
    void capabilitiesAreCheckedForEveryCaller() {
        role("bob", "author");
        role("dave", "contributor");
        refused(
                as("bob")
                        .contentType(ContentType.JSON)
                        .body(Map.of("title", token(), "type", "page", "status", "publish")),
                "POST",
                "/it/posts",
                403);
        refused(as(null).contentType(ContentType.JSON).body(Map.of("title", token())), "POST", "/it/posts", 401);
        refused(
                as("bob").contentType(ContentType.JSON).body(Map.of("title", token(), "type", "page")),
                "POST",
                "/it/posts",
                403);
        refused(
                as("dave").contentType(ContentType.JSON).body(Map.of("title", token(), "status", "publish")),
                "POST",
                "/it/posts",
                403);
        assertThat(createStatus("dave", Map.of("title", token()))).isEqualTo(200);
        assertThat(createStatus("bob", Map.of("title", token()))).isEqualTo(200);
    }

    @Test
    void titlesGiveUniqueSlugs() {
        String token = token();
        assertThat(create("alice", Map.of("title", "Hello " + token, "status", "publish"))
                        .getString("slug"))
                .isEqualTo("hello-" + token);
        assertThat(create("alice", Map.of("title", "Hello " + token, "status", "publish"))
                        .getString("slug"))
                .isEqualTo("hello-" + token + "-2");
        assertThat(create("alice", Map.of("title", "", "content", "x")).getString("slug"))
                .isEmpty();
        assertThat(as("alice")
                        .contentType(ContentType.JSON)
                        .body(Map.of("title", "", "content", ""))
                        .post("/it/posts")
                        .then()
                        .statusCode(400)
                        .extract()
                        .asString())
                .isEqualTo("{\"code\":\"empty_content\"}");
    }

    @Test
    void draftsAreHiddenFromOthers() {
        role("bob", "author");
        role("carol", "subscriber");
        long draft = create("bob", Map.of("title", token())).getLong("id");
        refused(as(null), "GET", "/it/posts/" + draft, 401);
        refused(as("carol"), "GET", "/it/posts/" + draft, 403);
        as("bob").get("/it/posts/" + draft).then().statusCode(200);
        as("alice").get("/it/posts/" + draft).then().statusCode(200);
    }

    @Test
    void postsGoToTheTrashComeBackAndAreDeleted() {
        long post =
                create("alice", Map.of("title", token(), "status", "publish")).getLong("id");
        assertThat(as("alice")
                        .post("/it/posts/" + post + "/trash")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("publish");
        assertThat(as("alice")
                        .get("/it/posts/" + post)
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getString("status"))
                .isEqualTo("trash");
        assertThat(as("alice")
                        .post("/it/posts/" + post + "/untrash")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("trash");
        assertThat(as("alice")
                        .get("/it/posts/" + post)
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getString("status"))
                .isEqualTo("draft");
        as("alice").delete("/it/posts/" + post + "?force=true").then().statusCode(200);
        refused(as("alice"), "GET", "/it/posts/" + post, 404);
    }

    @Test
    void aStaleVersionIsRefused() {
        JsonPath post = create("alice", Map.of("title", token(), "status", "publish"));
        long id = post.getLong("id");
        int version = post.getInt("version");
        JsonPath updated = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "first", "version", version))
                .put("/it/posts/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(updated.getString("title")).isEqualTo("first");
        assertThat(updated.getInt("version")).isEqualTo(version + 1);
        refused(
                as("alice").contentType(ContentType.JSON).body(Map.of("title", "second", "version", version)),
                "PUT",
                "/it/posts/" + id,
                409);
        assertThat(as("alice")
                        .get("/it/posts/" + id)
                        .then()
                        .extract()
                        .jsonPath()
                        .getString("title"))
                .isEqualTo("first");
    }

    @Test
    void queriesSearchAndPutStickyPostsFirst() {
        role("carol", "subscriber");
        String token = token();
        long oldest = create("alice", Map.of("title", token + " one", "status", "publish"))
                .getLong("id");
        create("alice", Map.of("title", token + " two", "status", "publish"));
        long newest = create("alice", Map.of("title", token + " three", "status", "publish"))
                .getLong("id");
        create("alice", Map.of("title", token + " draft"));
        JsonPath found = as(null).get("/it/posts?search=" + token)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(found.getLong("found")).isEqualTo(3);
        as("alice").post("/it/posts/" + oldest + "/sticky").then().statusCode(200);
        List<Long> ids = as(null).get("/it/posts?perPage=-1&stickyFirst=true")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("ids", Long.class);
        assertThat(ids.indexOf(oldest)).isNotNegative().isLessThan(ids.indexOf(newest));
        refused(as("carol"), "GET", "/it/posts?status=draft", 403);
        JsonPath drafts = as("alice")
                .get("/it/posts?status=draft&search=" + token)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(drafts.getLong("found")).isEqualTo(1);
    }

    @Test
    void anUnknownPostIsRefusedWithItsCode() {
        long missing = 9_000_000_000L + System.nanoTime() % 1_000_000;
        assertThat(as("alice")
                        .post("/it/posts/" + missing + "/trash")
                        .then()
                        .statusCode(400)
                        .extract()
                        .asString())
                .isEqualTo("{\"code\":\"invalid_post\"}");
    }
}
