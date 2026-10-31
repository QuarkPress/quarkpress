package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The users routes on a network: a site lists and shows only its own members, anonymous callers never see another
 * site's members or authors, preferences are kept per site, and WordPress's multisite user rules apply (only a super
 * admin edits or deletes other users or creates users). Main-site members: alice (administrator and super admin),
 * frank (administrator, not a super admin), carol (author, one published post on the main site), dave (contributor)
 * and erin (subscriber); gina is a member of the news site only, where she has a published post; dave is a member of
 * both sites.
 */
class UsersMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.multisite(UsersMultisiteDbTest.class);

    static final String PREFS = "{\"meta\":{\"persisted_preferences\":{\"core\":{\"fixedToolbar\":true},"
            + "\"_modified\":\"2026-01-15T12:00:00.000Z\"}}}";

    long alice;
    long frank;
    long carol;
    long dave;
    long erin;
    long gina;

    String news;
    SiteId newsId;
    String u;
    String fixture;

    @BeforeEach
    void network() {
        AgroalDataSource data = Jdbc.dataSource();
        StringBuilder letters = new StringBuilder("-");
        for (char c :
                UUID.randomUUID().toString().replace("-", "").substring(0, 10).toCharArray()) {
            letters.append((char) ('a' + Character.digit(c, 16) % 10));
        }
        u = letters.toString();
        news = "news" + u.substring(1);
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        newsId = SiteId.of(Jdbc.count(data, "select id from qp_site where path = ?", "/" + news + "/"));
        alice = member(data, "alice", "Alice Anders", TestData.MAIN, "administrator");
        TestData.superAdmin(data, UserId.of(alice));
        frank = member(data, "frank", "Frank Ford", TestData.MAIN, "administrator");
        carol = member(data, "carol", "Carol Chen", TestData.MAIN, "author");
        dave = member(data, "dave", "Dave Diaz", TestData.MAIN, "contributor");
        TestData.member(data, newsId, UserId.of(dave), "[\"contributor\"]");
        erin = member(data, "erin", "Erin Evans", TestData.MAIN, "subscriber");
        gina = member(data, "gina", "Gina Gold", newsId, "author");
        TestData.post(data, TestData.MAIN, UserId.of(carol), "post", "publish");
        TestData.post(data, newsId, UserId.of(gina), "post", "publish");
        // dave's only published post is on the news site
        TestData.post(data, newsId, UserId.of(dave), "post", "publish");
        fixture = "include=" + alice + "," + frank + "," + carol + "," + dave + "," + erin + "," + gina;
    }

    @Test
    void aSiteShowsOnlyItsOwnMembers() {
        // Part D 24: a member of another site only does not exist here, for a super admin too
        for (String bearer : new String[] {null, "test-" + alice}) {
            Response missing = Http.as(bearer).get("/wp-json/wp/v2/users/" + gina);
            expect(missing, 404, "rest_user_invalid_id", "Invalid user ID.");
            missing.then().header("Allow", nullValue());
        }
        Http.as(null).get("/" + news + "/wp-json/wp/v2/users/" + gina).then().statusCode(200);
        expect(
                Http.as(null).get("/" + news + "/wp-json/wp/v2/users/" + carol),
                404,
                "rest_user_invalid_id",
                "Invalid user ID.");
        // anonymous listings: authors of published posts of this site only
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?" + fixture))).containsExactly("carol");
        assertThat(slugs(Http.as(null).get("/" + news + "/wp-json/wp/v2/users?" + fixture)))
                .containsExactly("dave", "gina");
        // a super admin lists this site's members only
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?per_page=100&" + fixture)))
                .containsExactly("alice", "carol", "dave", "erin", "frank");
        assertThat(slugs(Http.as("test-" + alice).get("/" + news + "/wp-json/wp/v2/users?per_page=100&" + fixture)))
                .containsExactly("dave", "gina");
        // dave's published post on the news site makes him readable there, not on the main site
        expect(
                Http.as(null).get("/wp-json/wp/v2/users/" + dave),
                401,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to list users.");
        Http.as(null).get("/" + news + "/wp-json/wp/v2/users/" + dave).then().statusCode(200);
        assertThat(Http.as(null)
                        .get("/" + news + "/wp-json/wp/v2/users/" + gina)
                        .then()
                        .extract()
                        .jsonPath()
                        .getString("link"))
                .isEqualTo("http://localhost/" + news + "/?author=" + gina);
    }

    @Test
    void editContextDropsUsersTheCallerCannotEditAfterPaging() {
        // frank has list_users on the main site, but on a network only a super admin edits other users
        Response listed = Http.as("test-" + frank).get("/wp-json/wp/v2/users?context=edit&per_page=100&" + fixture);
        assertThat(slugs(listed)).containsExactly("frank");
        listed.then().header("X-WP-Total", "5");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?context=edit&per_page=100&" + fixture)))
                .containsExactly("alice", "carol", "dave", "erin", "frank");
        expect(
                Http.as("test-" + frank).get("/wp-json/wp/v2/users/" + carol + "?context=edit"),
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit this user.");
        // frank still reads carol's view (list_users)
        Http.as("test-" + frank).get("/wp-json/wp/v2/users/" + erin).then().statusCode(200);
    }

    @Test
    void preferencesArePerSite() {
        Http.as("test-" + dave)
                .contentType("application/json")
                .body(PREFS)
                .put("/" + news + "/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200)
                .body("meta.persisted_preferences.core.fixedToolbar", equalTo(true));
        assertThat(Jdbc.profileMeta(dave))
                .contains("\"wp_" + newsId.value() + "_persisted_preferences\"")
                .doesNotContain("\"wp_persisted_preferences\"");
        // the main site has none
        JsonNode main = json(Http.as("test-" + dave)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .asString());
        assertThat(main.get("meta").toString()).isEqualTo("{\"persisted_preferences\":[]}");
        // users/me reads the current user on a site it is not a member of
        Http.as("test-" + erin)
                .get("/" + news + "/wp-json/wp/v2/users/me?context=edit")
                .then()
                .statusCode(200)
                .body("slug", equalTo("erin" + u))
                .body("roles", equalTo(List.of()))
                .body("capabilities", equalTo(java.util.Map.of()));
        // but a write looks the user up as a member of the site, as WordPress does
        expect(
                Http.as("test-" + erin)
                        .contentType("application/json")
                        .body(PREFS)
                        .put("/" + news + "/wp-json/wp/v2/users/me"),
                404,
                "rest_user_invalid_id",
                "Invalid user ID.");
        assertThat(Jdbc.profileMeta(erin)).doesNotContain("persisted_preferences");
        // through users/<id>, the non-member's own id does not exist on that site
        expect(
                Http.as("test-" + erin).get("/" + news + "/wp-json/wp/v2/users/" + erin),
                404,
                "rest_user_invalid_id",
                "Invalid user ID.");
    }

    @Test
    void wordPresssMultisiteUserRules() {
        String newUser = "{\"username\":\"z\",\"email\":\"z@quarkpress.test\",\"password\":\"z-pass\"}";
        // Part D 27
        Response create = Http.as("test-" + frank)
                .contentType("application/json")
                .body(newUser)
                .post("/wp-json/wp/v2/users");
        expect(create, 403, "rest_cannot_create_user", "Sorry, you are not allowed to create new users.");
        create.then().header("Allow", "GET");
        expect(
                Http.as("test-" + frank)
                        .contentType("application/json")
                        .body(PREFS)
                        .put("/wp-json/wp/v2/users/" + dave),
                403,
                "rest_cannot_edit",
                "Sorry, you are not allowed to edit this user.");
        expect(
                Http.as("test-" + frank).delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=false"),
                403,
                "rest_user_cannot_delete",
                "Sorry, you are not allowed to delete this user.");
        expect(
                Http.as("test-" + alice)
                        .contentType("application/json")
                        .body(newUser)
                        .post("/wp-json/wp/v2/users"),
                501,
                "quarkpress_users_managed_externally",
                "Users are managed by the identity provider.");
        expect(
                Http.as("test-" + alice)
                        .contentType("application/json")
                        .body(PREFS)
                        .put("/wp-json/wp/v2/users/" + dave),
                501,
                "quarkpress_users_managed_externally",
                "Users are managed by the identity provider.");
        expect(
                Http.as("test-" + alice).delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=false"),
                501,
                "rest_cannot_delete",
                "The user cannot be deleted.");
        // a bad reassign wins over the network's 501
        Http.as("test-" + alice)
                .delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=abc")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.reassign", equalTo("Invalid user parameter(s)."));
        assertThat(Jdbc.profileMeta(dave)).doesNotContain("persisted_preferences");
        // Part D 17: the super admin may use every method on carol
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE");
        Http.as("test-" + frank)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .header("Allow", "GET");
    }

    private static void expect(Response response, int status, String code, String message) {
        response.then().statusCode(status).body("code", equalTo(code)).body("message", equalTo(message));
        assertThat(json(response.asString()).at("/data/status").intValue()).isEqualTo(status);
    }

    private long member(AgroalDataSource data, String name, String displayName, SiteId site, String role) {
        UserId user = TestData.profile(data, name + u, displayName, name + "@quarkpress.test");
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    /** The listed users' slugs without this test's suffix. */
    private List<String> slugs(Response response) {
        JsonNode list = json(response.then().statusCode(200).extract().asString());
        List<String> slugs = new ArrayList<>();
        for (JsonNode user : list) {
            String slug = user.get("slug").textValue();
            slugs.add(slug.endsWith(u) ? slug.substring(0, slug.length() - u.length()) : slug);
        }
        return slugs;
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
