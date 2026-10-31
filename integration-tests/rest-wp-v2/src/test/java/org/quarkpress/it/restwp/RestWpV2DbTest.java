package org.quarkpress.it.restwp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST API (wp/v2) end to end, with real Keycloak tokens: JVM mode here, the packaged application in
 * {@code RestWpV2IT}, and a native executable in CI. Every test runs on a network (the multisite install): the main
 * site is {@code localhost} at {@code /}, alice is a network administrator; one test adds a site in a subdirectory.
 */
@QuarkusTest
class RestWpV2DbTest {

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        role("bob", "editor");
        role("carol", "author");
        role("dave", "contributor");
        role("erin", "subscriber");
    }

    private long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    /** Gives {@code user} the role on the main site, as alice. */
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

    /** A new category on the main site, created by bob (an editor) over REST; answers its id. */
    private int category(String name, Integer parent) {
        Map<String, Object> body = parent == null ? Map.of("name", name) : Map.of("name", name, "parent", parent);
        return as("bob")
                .contentType(ContentType.JSON)
                .body(body)
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void theIndexAndTheNamespaceAnswer() {
        // main site of the network
        as(null).get("/wp-json/")
                .then()
                .statusCode(200)
                .header("Link", "<http://localhost/wp-json/>; rel=\"https://api.w.org/\"")
                .body("namespaces", contains("wp/v2"))
                .body("authentication.oidc.issuer", startsWith("http"));
        as(null).get("/?rest_route=/wp/v2").then().statusCode(200).body("namespace", equalTo("wp/v2"));
    }

    @Test
    void typesStatusesAndTaxonomiesAnswer() {
        // main site of the network
        as(null).get("/wp-json/wp/v2/types")
                .then()
                .statusCode(200)
                .body("post.slug", equalTo("post"))
                .body("page.rest_base", equalTo("pages"));
        as("carol")
                .get("/wp-json/wp/v2/types/post?context=edit")
                .then()
                .statusCode(200)
                .body("viewable", equalTo(true));
        as(null).get("/wp-json/wp/v2/statuses/publish").then().statusCode(200).body("name", equalTo("Published"));
        as(null).get("/wp-json/wp/v2/taxonomies")
                .then()
                .statusCode(200)
                .body("category.rest_base", equalTo("categories"))
                .body("post_tag.rest_base", equalTo("tags"));
    }

    @Test
    void usersMeCarriesTheTokensEmailAndThePreferencesRoundTrip() {
        // main site of the network; the e-mail is the token's verified one
        as("carol")
                .get("/wp-json/wp/v2/users/me?context=edit")
                .then()
                .statusCode(200)
                .body("email", equalTo("carol@quarkpress.test"))
                .body("name", equalTo("Carol Chen"))
                .body("slug", equalTo("carol"));
        as("carol")
                .contentType("application/json")
                .body("{\"meta\":{\"persisted_preferences\":{\"core\":{\"a\":1}}}}")
                .put("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200)
                .body("meta.persisted_preferences.core.a", equalTo(1));
        as("carol")
                .get("/wp-json/wp/v2/users/me?context=edit")
                .then()
                .statusCode(200)
                .body("meta.persisted_preferences.core.a", equalTo(1));
    }

    @Test
    void anAuthorWithAPublishedPostIsVisibleToEveryone() {
        // main site of the network: carol publishes through the setup resource; dave (contributor) never publishes
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("type", "post", "status", "publish", "title", token()))
                .post("/it/posts")
                .then()
                .statusCode(200);
        as(null).get("/wp-json/wp/v2/users/" + userId("carol"))
                .then()
                .statusCode(200)
                .body("slug", equalTo("carol"));
        as(null).get("/wp-json/wp/v2/users/" + userId("dave"))
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_user_cannot_view"))
                .body("message", equalTo("Sorry, you are not allowed to list users."));
    }

    @Test
    void termsFollowTheRoles() {
        // main site of the network
        as("carol")
                .contentType("application/json")
                .body("{\"name\":\"" + token() + "\"}")
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201);
        as("carol")
                .contentType("application/json")
                .body("{\"name\":\"" + token() + "\"}")
                .post("/wp-json/wp/v2/categories")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create terms in this taxonomy."));
        as("erin").get("/wp-json/wp/v2/categories").then().statusCode(200).header("Allow", "GET");
    }

    @Test
    void aCategoryIsCreatedReadUpdatedAndDeleted() {
        // main site of the network; bob is an editor
        String name = token();
        int id = category(name, null);
        as(null).get("/wp-json/wp/v2/categories/" + id).then().statusCode(200).body("name", equalTo(name));
        as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("description", "d"))
                .put("/wp-json/wp/v2/categories/" + id)
                .then()
                .statusCode(200)
                .body("description", equalTo("d"));
        as("bob")
                .delete("/wp-json/wp/v2/categories/" + id)
                .then()
                .statusCode(501)
                .body("code", equalTo("rest_trash_not_supported"))
                .body("message", equalTo("Terms do not support trashing. Set 'force=true' to delete."));
        as("bob")
                .delete("/wp-json/wp/v2/categories/" + id + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo(id));
        as(null).get("/wp-json/wp/v2/categories/" + id)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_term_invalid"));
    }

    @Test
    void embedAndFieldsShapeTheAnswer() {
        // main site of the network
        int parent = category(token(), null);
        int child = category(token(), parent);
        as(null).get("/wp-json/wp/v2/categories/" + child + "?_embed")
                .then()
                .statusCode(200)
                .body("_embedded.up[0].id", equalTo(parent));
        JsonPath fields = as(null).get("/wp-json/wp/v2/categories/" + child + "?_fields=id,parent")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(fields.getMap("$")).containsOnlyKeys("id", "parent").containsEntry("parent", parent);
    }

    @Test
    void optionsDescribesTheRouteForTheCaller() {
        // main site of the network: a subscriber may only read categories
        as("erin")
                .options("/wp-json/wp/v2/categories")
                .then()
                .statusCode(200)
                .header("Allow", "GET")
                .body("namespace", equalTo("wp/v2"))
                .body("methods", contains("GET", "POST"));
        as("bob")
                .options("/wp-json/wp/v2/categories?_fields=namespace")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST")
                .body("$", not(hasKey("methods")));
    }

    @Test
    void theMethodOverrideReachesTheDeleteEndpoint() {
        // main site of the network: an author may not delete a tag, an editor may
        int tag = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("name", token()))
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        as("carol")
                .header("X-HTTP-Method-Override", "DELETE")
                .post("/wp-json/wp/v2/tags/" + tag + "?force=true")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo("Sorry, you are not allowed to delete this term."));
        as("bob")
                .post("/wp-json/wp/v2/tags/" + tag + "?force=true&_method=DELETE")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));
    }

    @Test
    void anAnonymousFormPostCarriesTheRouteInItsBody() {
        // main site of the network
        as(null).contentType("application/x-www-form-urlencoded")
                .body("rest_route=/wp/v2/types/post")
                .post("/?_method=GET")
                .then()
                .statusCode(200)
                .body("slug", equalTo("post"));
        as(null).contentType("application/x-www-form-urlencoded")
                .body("rest_route=/wp/v2/tags&name=" + token())
                .post("/")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create terms in this taxonomy."));
    }

    @Test
    void settingsNeedManageOptions() {
        // multisite shape: no url or email setting
        as("alice")
                .get("/wp-json/wp/v2/settings")
                .then()
                .statusCode(200)
                .body("$", not(hasKey("url")))
                .body("$", not(hasKey("email")))
                .body("$", hasKey("title"));
        as("bob")
                .get("/wp-json/wp/v2/settings")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"))
                .body("message", equalTo("Sorry, you are not allowed to do that."));
    }

    @Test
    void anInvalidTokenIsRefusedBeforeMatching() {
        // main site of the network. Known difference from WordPress, which answers a failed credential anonymously:
        // QuarkPress refuses an invalid or expired token before matching, so token clients know to refresh
        given().header("Host", "localhost")
                .auth()
                .oauth2("not-a-token")
                .get("/wp-json/")
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .header("WWW-Authenticate", nullValue())
                .body("code", equalTo("rest_invalid_token"))
                .body("message", equalTo("The access token is invalid or has expired."))
                .body("data.status", equalTo(401));
    }

    @Test
    void anOptionWrittenThroughTheSetupResourceIsASetting() {
        // main site of the network; the original description is put back
        String original = as("alice")
                .get("/wp-json/wp/v2/settings")
                .then()
                .statusCode(200)
                .extract()
                .path("description");
        String description = token();
        try {
            as("bob")
                    .contentType(ContentType.JSON)
                    .body("\"" + description + "\"")
                    .put("/it/options/blogdescription")
                    .then()
                    .statusCode(403);
            as("alice")
                    .contentType(ContentType.JSON)
                    .body("\"" + description + "\"")
                    .put("/it/options/blogdescription")
                    .then()
                    .statusCode(200)
                    .body("changed", equalTo(true));
            as("alice").get("/wp-json/wp/v2/settings").then().statusCode(200).body("description", equalTo(description));
        } finally {
            as("alice")
                    .contentType(ContentType.JSON)
                    .body(Map.of("description", original))
                    .post("/wp-json/wp/v2/settings")
                    .then()
                    .statusCode(200)
                    .body("description", equalTo(original));
        }
    }

    @Test
    void aSubdirectorySiteServesItsOwnApi() {
        // a network site in a subdirectory of localhost, created by alice (network administrator)
        String path = "/s-" + token() + "/";
        String title = "Site " + token();
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", title))
                .post("/it/sites")
                .then()
                .statusCode(200);
        as(null).get(path + "wp-json/")
                .then()
                .statusCode(200)
                .header("Link", "<http://localhost" + path + "wp-json/>; rel=\"https://api.w.org/\"");
        JsonPath settings = as("alice")
                .get(path + "wp-json/wp/v2/settings")
                .then()
                .statusCode(200)
                .body("title", equalTo(title))
                .extract()
                .jsonPath();
        // its own default category only, none of the main site's
        as(null).get(path + "wp-json/wp/v2/categories?per_page=100")
                .then()
                .statusCode(200)
                .body("id", contains(settings.getInt("default_category")))
                .body("slug", contains("uncategorized"));
        // a settings write on this site only; the timezone check reads the zone list (also in a native executable)
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("timezone", "Europe/Brussels"))
                .post(path + "wp-json/wp/v2/settings")
                .then()
                .statusCode(200)
                .body("timezone", equalTo("Europe/Brussels"));
    }
}
