package org.quarkword.it.restwp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wprestparity.MediaFixtures;

/**
 * The REST API's front-end links with the front module installed: each test works on a new subdirectory site of the
 * network, which has WordPress's default structure for a network's new sites ({@code /%year%/%monthnum%/%day%/
 * %postname%/}), so the {@code link} and {@code permalink_template} fields, the term, author and search links and the
 * media item's {@code Link} follow it; an empty structure makes every one of them plain again. The values are
 * WordPress's for the same cast (the link-template facts of the front task, recorded with 7.1.3).
 */
@QuarkusTest
class PrettyLinksDbTest {

    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    private static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** A new subdirectory site, created by alice (network administrator); answers its path ({@code /pl-…/}). */
    private String site() {
        String path = "/pl-" + token() + "/";
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", "Links " + token()))
                .post("/it/sites")
                .then()
                .statusCode(200);
        return path;
    }

    /** A post of {@code type} on the site at {@code path}, written by alice; answers its id. */
    private long post(String path, String type, String status, Map<String, Object> fields) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("type", type, "status", status, "title", "T " + token(), "fields", fields))
                .post(path + "it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private JsonPath get(String user, String url) {
        return as(user).get(url).then().statusCode(200).extract().jsonPath();
    }

    private long userId(String user) {
        return get(user, "/it/me").getLong("user");
    }

    /** Makes carol an author of the site at {@code path}; answers her id. */
    private long carolOn(String path) {
        long carol = userId("carol");
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", carol, "role", "author"))
                .post(path + "it/memberships")
                .then()
                .statusCode(200);
        return carol;
    }

    private int category(String path, String name) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("name", name))
                .post(path + "wp-json/wp/v2/categories")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void postsPagesAndDraftsFollowTheStructure() {
        String path = site();
        String home = "http://localhost" + path;
        String slug = "pretty-" + token();
        long published = post(path, "post", "publish", Map.of("slug", slug, "date_gmt", "2026-03-04T10:00:00"));
        JsonPath item = get("alice", path + "wp-json/wp/v2/posts/" + published + "?context=edit");
        assertThat(item.getString("link")).isEqualTo(home + "2026/03/04/" + slug + "/");
        assertThat(item.getString("permalink_template")).isEqualTo(home + "2026/03/04/%postname%/");
        assertThat(as(null).get(path + "wp-json/wp/v2/posts/" + published)
                        .headers()
                        .getValues("Link"))
                .containsExactly("<" + home + "2026/03/04/" + slug + "/>; rel=\"alternate\"; type=text/html");

        String about = "about-" + token();
        long parent = post(path, "page", "publish", Map.of("slug", about));
        long child = post(path, "page", "publish", Map.of("slug", "team", "parent", parent));
        assertThat(get(null, path + "wp-json/wp/v2/pages/" + parent).getString("link"))
                .isEqualTo(home + about + "/");
        JsonPath team = get("alice", path + "wp-json/wp/v2/pages/" + child + "?context=edit");
        assertThat(team.getString("link")).isEqualTo(home + about + "/team/");
        assertThat(team.getString("permalink_template")).isEqualTo(home + about + "/%pagename%/");

        long draft = post(path, "post", "draft", Map.of("slug", "draft-" + token()));
        JsonPath drafted = get("alice", path + "wp-json/wp/v2/posts/" + draft + "?context=edit");
        assertThat(drafted.getString("link")).isEqualTo(home + "?p=" + draft);
        assertThat(drafted.getString("permalink_template"))
                .matches("\\Q" + home + "\\E\\d{4}/\\d{2}/\\d{2}/%postname%/");

        // the search's post item carries the same link
        String title = get(null, path + "wp-json/wp/v2/posts/" + published).getString("title.rendered");
        List<String> urls = get(null, path + "wp-json/wp/v2/search?search=" + title.substring(2) + "&_fields=url")
                .getList("url");
        assertThat(urls).containsExactly(home + "2026/03/04/" + slug + "/");
    }

    @Test
    void termsAuthorsAndTheSearchFollowTheStructure() {
        String path = site();
        String home = "http://localhost" + path;
        String name = "News " + token();
        int news = category(path, name);
        String newsSlug = get(null, path + "wp-json/wp/v2/categories/" + news).getString("slug");
        assertThat(get(null, path + "wp-json/wp/v2/categories/" + news).getString("link"))
                .isEqualTo(home + "category/" + newsSlug + "/");
        assertThat(get(null, path + "wp-json/wp/v2/search?type=term&search=" + name.substring(5) + "&_fields=url")
                        .getList("url"))
                .containsExactly(home + "category/" + newsSlug + "/");

        long carol = carolOn(path);
        JsonPath user = get("alice", path + "wp-json/wp/v2/users/" + carol);
        assertThat(user.getString("link")).isEqualTo(home + "author/" + user.getString("slug") + "/");
    }

    @Test
    void theIndexNamesTheSiteurlOptionAndTheHome() {
        String path = site();
        String home = "http://localhost" + path.substring(0, path.length() - 1);
        as("alice")
                .contentType(ContentType.JSON)
                .body("\"" + home + "-app\"")
                .put(path + "it/options/siteurl")
                .then()
                .statusCode(200);
        JsonPath index = get(null, path + "wp-json/");
        assertThat(index.getString("url")).isEqualTo(home + "-app");
        assertThat(index.getString("home")).isEqualTo(home);
    }

    @Test
    void aMediaItemsAlternateLinkIsItsPage() {
        String path = site();
        Response upload = as("alice")
                .multiPart("file", "logo-" + token() + ".jpg", MediaFixtures.jpeg(16, 12), "image/jpeg")
                .post(path + "wp-json/wp/v2/media");
        upload.then().statusCode(201);
        int id = upload.path("id");
        String slug = upload.path("slug");
        String page = "http://localhost" + path + slug + "/";
        Response item = as(null).get(path + "wp-json/wp/v2/media/" + id);
        item.then().statusCode(200);
        assertThat(item.jsonPath().getString("link")).isEqualTo(page);
        assertThat(item.headers().getValues("Link"))
                .containsExactly("<" + page + ">; rel=\"alternate\"; type=text/html");
    }

    @Test
    void anEmptyStructureMakesEveryLinkPlain() {
        String path = site();
        String home = "http://localhost" + path;
        as("alice")
                .contentType(ContentType.JSON)
                .body("\"\"")
                .put(path + "it/options/permalink_structure")
                .then()
                .statusCode(200);
        long published = post(path, "post", "publish", Map.of("date_gmt", "2026-03-04T10:00:00"));
        JsonPath item = get("alice", path + "wp-json/wp/v2/posts/" + published + "?context=edit");
        assertThat(item.getString("link")).isEqualTo(home + "?p=" + published);
        assertThat(item.getString("permalink_template")).isEqualTo(home + "?p=" + published);
        long page = post(path, "page", "publish", Map.of());
        assertThat(get(null, path + "wp-json/wp/v2/pages/" + page).getString("link"))
                .isEqualTo(home + "?page_id=" + page);
        int news = category(path, "News " + token());
        assertThat(get(null, path + "wp-json/wp/v2/categories/" + news).getString("link"))
                .isEqualTo(home + "?cat=" + news);
        long carol = carolOn(path);
        assertThat(get("alice", path + "wp-json/wp/v2/users/" + carol).getString("link"))
                .isEqualTo(home + "?author=" + carol);
        // the REST hrefs stay on /wp-json/
        assertThat(get(null, path + "wp-json/wp/v2/posts/" + published).getString("_links.self[0].href"))
                .isEqualTo(home + "wp-json/wp/v2/posts/" + published);
    }
}
