package org.quarkpress.it.posts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TermsDbTest {

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

    private int createTerm(String user, String taxonomy, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/terms/" + taxonomy)
                .statusCode();
    }

    private JsonPath post(String user, Map<String, Object> body) {
        return as(user).contentType(ContentType.JSON)
                .body(body)
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private List<String> slugs(String user, long post, String taxonomy) {
        return as(user).get("/it/posts/" + post + "/terms/" + taxonomy)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("slug", String.class);
    }

    @Test
    void contributorsCreateTagsAndOnlyEditorsCreateCategories() {
        role("dave", "contributor");
        role("bob", "editor");
        assertThat(createTerm("dave", "post_tag", Map.of("name", token()))).isEqualTo(200);
        assertThat(createTerm("dave", "category", Map.of("name", token()))).isEqualTo(403);
        assertThat(createTerm(null, "post_tag", Map.of("name", token()))).isEqualTo(401);
        assertThat(createTerm("bob", "category", Map.of("name", token()))).isEqualTo(200);
        String name = token();
        assertThat(createTerm("bob", "category", Map.of("name", name))).isEqualTo(200);
        JsonPath duplicate = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("name", name.toUpperCase(Locale.ROOT)))
                .post("/it/terms/category")
                .then()
                .statusCode(400)
                .extract()
                .jsonPath();
        assertThat(duplicate.getString("code")).isEqualTo("term_exists");
    }

    @Test
    void aSavedPostGetsTheDefaultCategoryAndTagsByName() {
        role("bob", "editor");
        long id = post("bob", Map.of("title", token(), "status", "publish")).getLong("id");
        assertThat(slugs("bob", id, "category")).containsExactly("uncategorized");
        String tag = token();
        as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("names", List.of(tag, " ", tag)))
                .put("/it/posts/" + id + "/terms/post_tag")
                .then()
                .statusCode(200);
        assertThat(slugs(null, id, "post_tag")).containsExactly(tag);
        JsonPath listed = as(null).get("/it/terms?taxonomy=post_tag&search=" + tag)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(listed.getList("slug", String.class)).containsExactly(tag);
        assertThat(listed.getList("count", Integer.class)).containsExactly(1);
    }

    @Test
    void theDefaultCategoryCannotBeDeleted() {
        role("bob", "editor");
        post("bob", Map.of("title", token(), "status", "publish"));
        long uncategorized = as(null).get("/it/terms?taxonomy=category&slug=uncategorized&hideEmpty=false")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");
        as("alice").delete("/it/terms/category/" + uncategorized).then().statusCode(403);
        as("alice").delete("/it/terms/category/999999999").then().statusCode(404);
    }

    @Test
    void assigningTermsToAMissingPostAnswersInvalidPostNotAServerError() {
        role("bob", "editor");
        JsonPath refused = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("names", List.of(token())))
                .put("/it/posts/999999999/terms/post_tag")
                .then()
                .statusCode(400)
                .extract()
                .jsonPath();
        assertThat(refused.getString("code")).isEqualTo("invalid_post");
    }

    @Test
    void anEditorRenamesAndReslugsACategory() {
        role("bob", "editor");
        long id = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("name", token()))
                .post("/it/terms/category")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        String slug = token();
        JsonPath updated = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Renamed " + slug, "slug", slug))
                .put("/it/terms/category/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(updated.getLong("id")).isEqualTo(id);
        assertThat(updated.getString("name")).isEqualTo("Renamed " + slug);
        assertThat(updated.getString("slug")).isEqualTo(slug);
    }
}
