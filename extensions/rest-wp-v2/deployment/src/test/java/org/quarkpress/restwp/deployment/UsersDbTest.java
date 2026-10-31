package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The users routes on a single site, where WordPress's single-site rules apply: the administrator alice passes
 * {@code create_users}, {@code edit_user} and {@code delete_user} on others. Main-site members alice (administrator),
 * bob (editor), carol (author, one published post), dave (contributor, only a draft) and erin (subscriber, only a
 * private post), with the dev realm's e-mail addresses. Each test makes its own five members (nicenames carry a
 * suffix of letters) and lists only them, through {@code include}, so other tests' users never count.
 */
class UsersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.singleSite(UsersDbTest.class);

    static final String PREFS = "{\"meta\":{\"persisted_preferences\":{\"core\":{\"fixedToolbar\":true},"
            + "\"_modified\":\"2026-01-15T12:00:00.000Z\"}}}";

    long alice;
    long bob;
    long carol;
    long dave;
    long erin;

    /** The nicename suffix of this test's members: letters only, so no search term of the tests matches it. */
    String u;

    String fixture;

    @BeforeEach
    void members() {
        AgroalDataSource data = Jdbc.dataSource();
        StringBuilder letters = new StringBuilder("-");
        for (char c :
                UUID.randomUUID().toString().replace("-", "").substring(0, 10).toCharArray()) {
            letters.append((char) ('a' + Character.digit(c, 16) % 10));
        }
        u = letters.toString();
        alice = member(data, "alice", "Alice Anders", "administrator");
        bob = member(data, "bob", "Bob Baker", "editor");
        carol = member(data, "carol", "Carol Chen", "author");
        dave = member(data, "dave", "Dave Diaz", "contributor");
        erin = member(data, "erin", "Erin Evans", "subscriber");
        TestData.post(data, TestData.MAIN, UserId.of(carol), "post", "publish");
        TestData.post(data, TestData.MAIN, UserId.of(dave), "post", "draft");
        TestData.post(data, TestData.MAIN, UserId.of(erin), "post", "private");
        fixture = "include=" + alice + "," + bob + "," + carol + "," + dave + "," + erin;
    }

    @Test
    void permissionChecksInWordPresssOrderWithTheirCodes() {
        expect(
                Http.as("test-" + carol).get("/wp-json/wp/v2/users?roles=author"),
                403,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to filter users by role.");
        expect(
                Http.as("test-" + carol).get("/wp-json/wp/v2/users?capabilities=edit_posts"),
                403,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to filter users by capability.");
        expect(
                Http.as(null).get("/wp-json/wp/v2/users?context=edit"),
                401,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit users.");
        expect(
                Http.as(null).get("/wp-json/wp/v2/users?orderby=email"),
                401,
                "rest_forbidden_orderby",
                "Sorry, you are not allowed to order users by this parameter.");
        expect(
                Http.as("test-" + erin).get("/wp-json/wp/v2/users?orderby=registered_date"),
                403,
                "rest_forbidden_orderby",
                "Sorry, you are not allowed to order users by this parameter.");
        expect(
                Http.as("test-" + erin).get("/wp-json/wp/v2/users?who=authors"),
                403,
                "rest_forbidden_who",
                "Sorry, you are not allowed to query users by this parameter.");
        // roles is checked before capabilities, which comes before the context
        expect(
                Http.as(null).get("/wp-json/wp/v2/users?capabilities=read&roles=x&context=edit"),
                401,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to filter users by role.");
        Http.as(null)
                .get("/wp-json/wp/v2/users?per_page=0")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"));
    }

    @Test
    void whoIsVisibleWithoutListUsers() {
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?" + fixture))).containsExactly("carol");
        Http.as(null).get("/wp-json/wp/v2/users?" + fixture).then().header("X-WP-Total", "1");
        assertThat(slugs(Http.as("test-" + dave).get("/wp-json/wp/v2/users?who=authors&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?per_page=100&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave", "erin");
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?has_published_posts=page&" + fixture)))
                .isEmpty();
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?has_published_posts=true&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?has_published_posts[]=page&has_published_posts[]=post&" + fixture)))
                .containsExactly("carol");
        // false keeps the default: the restriction for a caller without list_users, none for alice
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?has_published_posts=false&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?has_published_posts=false&" + fixture)))
                .hasSize(5);
        Http.as(null)
                .get("/wp-json/wp/v2/users?has_published_posts=x")
                .then()
                .statusCode(400)
                .body(
                        "data.params.has_published_posts",
                        equalTo("has_published_posts[0] is not one of post, page, attachment, and wp_block."));
        // an empty list keeps the restriction, whether sent as JSON or as an empty query value
        for (Response empty : List.of(
                Http.as(null)
                        .contentType("application/json")
                        .body("{\"has_published_posts\":[]}")
                        .get("/wp-json/wp/v2/users?" + fixture),
                Http.as(null).get("/wp-json/wp/v2/users?has_published_posts=&" + fixture))) {
            empty.then().header("X-WP-Total", "1");
            assertThat(slugs(empty)).containsExactly("carol");
        }
        // the subscriber erin and the contributor dave see only carol too
        assertThat(slugs(Http.as("test-" + erin).get("/wp-json/wp/v2/users?" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + dave).get("/wp-json/wp/v2/users?" + fixture)))
                .containsExactly("carol");
    }

    @Test
    void roleAndCapabilityFiltersIgnoreCaseAndOrderAsAsked() {
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?roles=Editor&" + fixture)))
                .containsExactly("bob");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?roles=author,subscriber&" + fixture)))
                .containsExactly("carol", "erin");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?capabilities=EDIT_posts&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?orderby=id&order=desc&" + fixture)))
                .containsExactly("erin", "dave", "carol", "bob", "alice");
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?orderby=include&include=" + dave + "," + alice + "," + carol)))
                .containsExactly("dave", "alice", "carol");
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?slug=dave" + u + ",bob" + u + "&orderby=include_slugs")))
                .containsExactly("dave", "bob");
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?slug=alice" + u + ",bob" + u + ",carol" + u + "&exclude=" + bob + ","
                                + carol)))
                .containsExactly("alice");
        // paging: the totals count every match
        Response page = Http.as("test-" + alice).get("/wp-json/wp/v2/users?per_page=2&page=2&" + fixture);
        assertThat(slugs(page)).containsExactly("carol", "dave");
        page.then().header("X-WP-Total", "5").header("X-WP-TotalPages", "3");
    }

    @Test
    void whoAuthorsIgnoresRolesAndCapabilities() {
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?who=authors&roles=subscriber&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?who=authors&capabilities=read&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave");
        // the published-posts filter still applies with it
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?who=authors&has_published_posts=true&" + fixture)))
                .containsExactly("carol");
        // roles is refused before who for a caller without list_users
        expect(
                Http.as("test-" + dave).get("/wp-json/wp/v2/users?who=authors&roles=author"),
                403,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to filter users by role.");
    }

    @Test
    void everyUserIsAUserOfTheSingleSite() {
        // a profile without a membership row (no role): listed and readable as WordPress lists every user
        AgroalDataSource data = Jdbc.dataSource();
        long roleless = TestData.profile(data, "rolf" + u, "Rolf Roth", "rolf@quarkpress.test")
                .value();
        String ours = fixture + "," + roleless;
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?per_page=100&" + ours)))
                .containsExactly("alice", "bob", "carol", "dave", "erin", "rolf");
        Http.as("test-" + alice).get("/wp-json/wp/v2/users?" + ours).then().header("X-WP-Total", "6");
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?" + ours))).containsExactly("carol");
        assertThat(slugs(Http.as("test-" + dave).get("/wp-json/wp/v2/users?who=authors&" + ours)))
                .containsExactly("alice", "bob", "carol", "dave");
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/users/" + roleless + "?context=edit")
                .then()
                .statusCode(200)
                .body("roles", equalTo(List.of()))
                .body("extra_capabilities", equalTo(java.util.Map.of()));
        expect(
                Http.as(null).get("/wp-json/wp/v2/users/" + roleless),
                401,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to list users.");
        Http.as("test-" + roleless).get("/wp-json/wp/v2/users/me").then().statusCode(200);
        // with a published post, anyone sees it
        TestData.post(data, TestData.MAIN, UserId.of(roleless), "post", "publish");
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?" + ours))).containsExactly("carol", "rolf");
        Http.as(null).get("/wp-json/wp/v2/users/" + roleless).then().statusCode(200);
    }

    @Test
    void headCountsOnly() {
        Http.as("test-" + alice)
                .head("/wp-json/wp/v2/users?" + fixture)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "5")
                .body(equalTo(""));
    }

    @Test
    void searchColumnsDependOnListUsers() {
        // without list_users the e-mail is never searched
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?search=quarkpress.test&" + fixture)))
                .isEmpty();
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?search=quarkpress.test&" + fixture)))
                .containsExactly("alice", "bob", "carol", "dave", "erin");
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?search=aro&" + fixture)))
                .containsExactly("carol");
        // REST verification list item 24: the e-mail column is dropped without list_users, the request not refused
        Response email =
                Http.as(null).get("/wp-json/wp/v2/users?search=quarkpress.test&search_columns[]=email&" + fixture);
        email.then().statusCode(200).header("X-WP-Total", "0");
        assertThat(slugs(email)).isEmpty();
        // REST verification list item 24, as erin (no list_users)
        assertThat(slugs(Http.as("test-" + erin).get("/wp-json/wp/v2/users?search=car&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + erin).get("/wp-json/wp/v2/users?search=" + carol + "&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + erin).get("/wp-json/wp/v2/users?search=Chen&" + fixture)))
                .containsExactly("carol");
        // a requested column the caller may not search leaves none: WordPress searches the four public ones
        assertThat(slugs(Http.as(null).get("/wp-json/wp/v2/users?search=car&search_columns[]=email&" + fixture)))
                .containsExactly("carol");
        // the term is trimmed and its wildcards stripped; search_columns narrows the columns
        assertThat(slugs(Http.as("test-" + erin).get("/wp-json/wp/v2/users?search=%20*Chen*%20&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + erin)
                        .get("/wp-json/wp/v2/users?search=Chen&search_columns[]=slug&" + fixture)))
                .isEmpty();
        // with list_users and no columns, WordPress's choice by the term: "@" searches only the e-mail
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?search=carol@&" + fixture)))
                .containsExactly("carol");
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?search=Chen@&" + fixture)))
                .isEmpty();
        assertThat(slugs(Http.as("test-" + alice).get("/wp-json/wp/v2/users?search=" + dave + "&" + fixture)))
                .containsExactly("dave");
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?search=erin@&search_columns[]=name&" + fixture)))
                .isEmpty();
        assertThat(slugs(Http.as("test-" + alice)
                        .get("/wp-json/wp/v2/users?search=erin@&search_columns[]=email&" + fixture)))
                .containsExactly("erin");
    }

    @Test
    void listsAreBounded() {
        StringBuilder ids = new StringBuilder("[1");
        StringBuilder names = new StringBuilder("[\"n1\"");
        for (int i = 2; i <= 1001; i++) {
            ids.append(',').append(i);
            names.append(",\"n").append(i).append('"');
        }
        ids.append(']');
        names.append(']');
        for (String list : List.of("include", "exclude", "slug", "roles", "capabilities")) {
            String many = list.endsWith("clude") ? ids.toString() : names.toString();
            Http.as("test-" + alice)
                    .contentType("application/json")
                    .body("{\"" + list + "\":" + many + "}")
                    .get("/wp-json/wp/v2/users")
                    .then()
                    .statusCode(400)
                    .body("code", equalTo("rest_invalid_param"))
                    .body("data.params." + list, equalTo(list + " must contain at most 1000 items."));
        }
    }

    @Test
    void itemReadRules() {
        expect(Http.as(null).get("/wp-json/wp/v2/users/987654321"), 404, "rest_user_invalid_id", "Invalid user ID.");
        Http.as(null).get("/wp-json/wp/v2/users/987654321").then().header("Allow", nullValue());
        expect(
                Http.as(null).get("/wp-json/wp/v2/users/" + erin),
                401,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to list users.");
        Http.as(null).get("/wp-json/wp/v2/users/" + erin).then().header("Allow", nullValue());
        // erin's private post is readable by bob
        Http.as("test-" + bob).get("/wp-json/wp/v2/users/" + erin).then().statusCode(200);
        // REST verification list item 24
        expect(
                Http.as("test-" + dave).get("/wp-json/wp/v2/users/" + erin),
                403,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to list users.");
        Http.as("test-" + dave).get("/wp-json/wp/v2/users/" + erin).then().header("Allow", nullValue());
        // a drafts-only contributor is visible to nobody without list_users or edit_user
        expect(
                Http.as("test-" + erin).get("/wp-json/wp/v2/users/" + dave),
                403,
                "rest_user_cannot_view",
                "Sorry, you are not allowed to list users.");
        Http.as("test-" + alice).get("/wp-json/wp/v2/users/" + dave).then().statusCode(200);
        // REST verification list items 17 and 25: Allow per caller
        Http.as(null)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .header("Allow", "GET");
        Http.as("test-" + carol)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH");
        Http.as("test-" + alice)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE");
        Http.as("test-" + erin)
                .get("/wp-json/wp/v2/users/" + erin + "?context=edit")
                .then()
                .statusCode(200)
                .body("email", equalTo("erin@quarkpress.test"))
                .body("locale", equalTo("en_US"));
        expect(
                Http.as("test-" + carol).get("/wp-json/wp/v2/users/" + bob + "?context=edit"),
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit this user.");
        expect(
                Http.as(null).get("/wp-json/wp/v2/users/" + carol + "?context=edit"),
                401,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit this user.");
    }

    @Test
    void anEmailIsNeverShownWithoutTheRightToEdit() {
        JsonNode view = json(Http.as(null)
                .get("/wp-json/wp/v2/users/" + carol + "?_fields=id,email,roles,meta")
                .asString());
        assertThat(view.toString()).isEqualTo("{\"id\":" + carol + ",\"meta\":[]}");
        // a body field is built only when its exact name is selected: a child of it alone selects nothing
        assertThat(Http.as(null)
                        .get("/wp-json/wp/v2/users/" + carol + "?_fields=name.x")
                        .asString())
                .isEqualTo("[]");
        assertThat(Http.as(null)
                        .get("/wp-json/wp/v2/users/" + carol + "?_fields=id,avatar_urls.24")
                        .asString())
                .isEqualTo("{\"id\":" + carol + "}");
        assertThat(Http.as("test-" + erin)
                        .get("/wp-json/wp/v2/users?_fields=email&" + fixture)
                        .asString())
                .doesNotContain("quarkpress.test");
        assertThat(Http.as(null).get("/wp-json/wp/v2/users?" + fixture).asString())
                .doesNotContain("quarkpress.test");
    }

    @Test
    void fieldsInEachContext() {
        JsonNode view = json(Http.as(null).get("/wp-json/wp/v2/users/" + carol).asString());
        assertThat(fieldNames(view))
                .containsExactly("id", "name", "url", "description", "link", "slug", "avatar_urls", "meta", "_links");
        assertThat(view.get("id").isIntegralNumber()).isTrue();
        assertThat(view.get("id").longValue()).isEqualTo(carol);
        assertThat(view.get("name").textValue()).isEqualTo("Carol Chen");
        assertThat(view.get("url").textValue()).isEmpty();
        assertThat(view.get("description").textValue()).isEmpty();
        assertThat(view.get("slug").textValue()).isEqualTo("carol" + u);
        assertThat(view.get("link").asText()).isEqualTo("http://localhost/?author=" + carol);
        assertThat(view.get("meta").toString()).isEqualTo("[]");
        assertThat(view.at("/avatar_urls/48").textValue())
                .isEqualTo("https://secure.gravatar.com/avatar/"
                        + "b2b80e9a91cf0031a0929970d723ec1a622ab9f122eedb00a13c11a1caa42f9c?s=48&d=mm&r=g");
        assertThat(view.at("/_links/self/0/href").textValue())
                .isEqualTo("http://localhost/wp-json/wp/v2/users/" + carol);
        assertThat(view.at("/_links/collection/0/href").textValue()).isEqualTo("http://localhost/wp-json/wp/v2/users");
        JsonNode edit = json(Http.as("test-" + alice)
                .get("/wp-json/wp/v2/users/" + carol + "?context=edit")
                .asString());
        assertThat(fieldNames(edit))
                .containsExactly(
                        "id",
                        "username",
                        "name",
                        "first_name",
                        "last_name",
                        "email",
                        "url",
                        "description",
                        "link",
                        "locale",
                        "nickname",
                        "slug",
                        "roles",
                        "registered_date",
                        "capabilities",
                        "extra_capabilities",
                        "avatar_urls",
                        "meta",
                        "_links");
        assertThat(edit.get("username").textValue()).isEqualTo("carol" + u);
        assertThat(edit.get("nickname").textValue()).isEqualTo("carol" + u);
        assertThat(edit.get("first_name").textValue()).isEmpty();
        assertThat(edit.get("last_name").textValue()).isEmpty();
        assertThat(edit.get("email").textValue()).isEqualTo("carol@quarkpress.test");
        assertThat(edit.get("roles").toString()).isEqualTo("[\"author\"]");
        assertThat(edit.get("extra_capabilities").toString()).isEqualTo("{\"author\":true}");
        assertThat(edit.at("/capabilities/level_2")).isEqualTo(BooleanNode.TRUE);
        assertThat(edit.get("registered_date").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00");
        // REST verification list item 25
        assertThat(edit.get("meta").toString()).isEqualTo("{\"persisted_preferences\":[]}");
        JsonNode erinEdit = json(Http.as("test-" + erin)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .asString());
        assertThat(erinEdit.get("capabilities").toString())
                .isEqualTo("{\"read\":true,\"level_0\":true,\"subscriber\":true}");
        assertThat(erinEdit.get("extra_capabilities").toString()).isEqualTo("{\"subscriber\":true}");
        assertThat(erinEdit.get("locale").textValue()).isEqualTo("en_US");
        JsonNode embed = json(Http.as(null)
                .get("/wp-json/wp/v2/users/" + carol + "?context=embed")
                .asString());
        assertThat(fieldNames(embed))
                .containsExactly("id", "name", "url", "description", "link", "slug", "avatar_urls", "_links");
        // the initials default image: the display name's initials as the last parameter
        Jdbc.option(TestData.MAIN, "avatar_default", "\"initials\"");
        try {
            assertThat(json(Http.as(null).get("/wp-json/wp/v2/users/" + carol).asString())
                            .at("/avatar_urls/24")
                            .textValue())
                    .endsWith("?s=24&d=initials&r=g&initials=CC");
        } finally {
            Jdbc.option(TestData.MAIN, "avatar_default", "\"mystery\"");
        }
        // without show_avatars there are no avatar URLs
        Jdbc.option(TestData.MAIN, "show_avatars", "\"\"");
        try {
            JsonNode noAvatars =
                    json(Http.as(null).get("/wp-json/wp/v2/users/" + carol).asString());
            assertThat(fieldNames(noAvatars))
                    .containsExactly("id", "name", "url", "description", "link", "slug", "meta", "_links");
        } finally {
            Jdbc.option(TestData.MAIN, "show_avatars", "\"1\"");
        }
    }

    @Test
    void me() {
        Http.as(null)
                .get("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_not_logged_in\",\"message\":\"You are not currently logged in.\","
                        + "\"data\":{\"status\":401}}"));
        Http.as("test-" + erin)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body("slug", equalTo("erin" + u));
        // REST verification list item 26
        Response anonymousPut = Http.as(null)
                .contentType("application/json")
                .body("{\"name\":\"x\"}")
                .put("/wp-json/wp/v2/users/me");
        expect(anonymousPut, 404, "rest_user_invalid_id", "Invalid user ID.");
        anonymousPut.then().header("Allow", "GET");
        expect(
                Http.as(null).delete("/wp-json/wp/v2/users/me?force=true&reassign=false"),
                404,
                "rest_user_invalid_id",
                "Invalid user ID.");
    }

    @Test
    void preferencesAreTheOnlyWritableFieldAndArePerSite() {
        Http.as("test-" + dave)
                .contentType("application/json")
                .body(PREFS)
                .put("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200)
                .body("meta.persisted_preferences.core.fixedToolbar", equalTo(true))
                .body("email", equalTo("dave@quarkpress.test"));
        assertThat(Jdbc.profileMeta(dave)).contains("\"wp_persisted_preferences\"");
        expect(
                Http.as("test-" + dave)
                        .contentType("application/json")
                        .body("{\"name\":\"D\"}")
                        .put("/wp-json/wp/v2/users/me"),
                501,
                "quarkpress_users_managed_externally",
                "Users are managed by the identity provider.");
        // preferences and anything else: nothing is written
        expect(
                Http.as("test-" + dave)
                        .contentType("application/json")
                        .body("{\"name\":\"D\",\"meta\":{\"persisted_preferences\":{\"x\":1}}}")
                        .put("/wp-json/wp/v2/users/" + dave),
                501,
                "quarkpress_users_managed_externally",
                "Users are managed by the identity provider.");
        expect(
                Http.as("test-" + dave)
                        .contentType("application/json")
                        .body("{\"meta\":{\"persisted_preferences\":{\"x\":1},\"other\":1}}")
                        .patch("/wp-json/wp/v2/users/me"),
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
                Http.as("test-" + dave)
                        .contentType("application/json")
                        .body(PREFS)
                        .put("/wp-json/wp/v2/users/" + erin),
                403,
                "rest_cannot_edit",
                "Sorry, you are not allowed to edit this user.");
        expect(
                Http.as(null).contentType("application/json").body(PREFS).put("/wp-json/wp/v2/users/" + erin),
                401,
                "rest_cannot_edit",
                "Sorry, you are not allowed to edit this user.");
        assertThat(Jdbc.profileMeta(erin)).doesNotContain("persisted_preferences");
        // roles to set are checked first, against promote_user (as WordPress answered a subscriber's request)
        expect(
                Http.as("test-" + erin).post("/wp-json/wp/v2/users/" + carol + "?password=Gen-pass-1&roles=editor"),
                403,
                "rest_cannot_edit_roles",
                "Sorry, you are not allowed to edit roles of this user.");
        expect(
                Http.as(null).post("/wp-json/wp/v2/users/" + carol + "?roles=editor"),
                401,
                "rest_cannot_edit_roles",
                "Sorry, you are not allowed to edit roles of this user.");
        expect(
                Http.as("test-" + erin).post("/wp-json/wp/v2/users/" + carol + "?password=Gen-pass-1"),
                403,
                "rest_cannot_edit",
                "Sorry, you are not allowed to edit this user.");
        // REST verification list item 26: a non-object value is refused at the top level and the stored value stays
        Http.as("test-" + dave)
                .contentType("application/json")
                .body("{\"meta\":{\"persisted_preferences\":\"x\"}}")
                .put("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_type\","
                        + "\"message\":\"meta.persisted_preferences is not of type object.\",\"data\":{\"status\":400},"
                        + "\"additional_data\":[{\"param\":\"meta.persisted_preferences\"}]}"));
        Http.as("test-" + dave)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .then()
                .body("meta.persisted_preferences._modified", equalTo("2026-01-15T12:00:00.000Z"))
                .body("meta.persisted_preferences.core.fixedToolbar", equalTo(true));
        // the preferences are not shown in the view context
        Http.as("test-" + dave).get("/wp-json/wp/v2/users/me").then().body("meta", equalTo(List.of()));
        // REST verification list item 25: null removes them; the same write through users/<id> on oneself, by POST
        Http.as("test-" + dave)
                .contentType("application/json")
                .body("{\"meta\":{\"persisted_preferences\":null}}")
                .post("/wp-json/wp/v2/users/" + dave)
                .then()
                .statusCode(200);
        JsonNode cleared = json(Http.as("test-" + dave)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .asString());
        assertThat(cleared.get("meta").toString()).isEqualTo("{\"persisted_preferences\":[]}");
        assertThat(Jdbc.profileMeta(dave)).doesNotContain("persisted_preferences");
    }

    @Test
    void creatingAndDeletingAreRefusedAfterThePermissionCheck() {
        String newUser = "{\"username\":\"z\",\"email\":\"z@quarkpress.test\",\"password\":\"z-pass\"}";
        Response refused = Http.as("test-" + bob)
                .contentType("application/json")
                .body(newUser)
                .post("/wp-json/wp/v2/users");
        expect(refused, 403, "rest_cannot_create_user", "Sorry, you are not allowed to create new users.");
        refused.then().header("Allow", "GET");
        expect(
                Http.as(null).contentType("application/json").body(newUser).post("/wp-json/wp/v2/users"),
                401,
                "rest_cannot_create_user",
                "Sorry, you are not allowed to create new users.");
        // known difference: WordPress creates the user (201); QuarkPress answers 501 after the create_users check
        expect(
                Http.as("test-" + alice)
                        .contentType("application/json")
                        .body(newUser)
                        .post("/wp-json/wp/v2/users"),
                501,
                "quarkpress_users_managed_externally",
                "Users are managed by the identity provider.");
        // as in WordPress, the required arguments are checked before the permission
        Http.as("test-" + bob)
                .contentType("application/json")
                .body("{\"username\":\"z\"}")
                .post("/wp-json/wp/v2/users")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_missing_callback_param"))
                .body("message", equalTo("Missing parameter(s): email, password"));
        expect(
                Http.as("test-" + bob).delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=false"),
                403,
                "rest_user_cannot_delete",
                "Sorry, you are not allowed to delete this user.");
        expect(
                Http.as("test-" + alice).delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=false"),
                501,
                "rest_cannot_delete",
                "The user cannot be deleted.");
        expect(
                Http.as("test-" + alice).delete("/wp-json/wp/v2/users/" + erin + "?force=true&reassign=" + bob),
                501,
                "rest_cannot_delete",
                "The user cannot be deleted.");
        Http.as("test-" + alice)
                .delete("/wp-json/wp/v2/users/" + erin + "?force=true")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_missing_callback_param"));
        // a bad reassign is refused while the arguments are sanitized: before the permission, the 404 and the 501
        for (String bearer : new String[] {null, "test-" + alice}) {
            for (String target : new String[] {Long.toString(erin), "987654321", "me"}) {
                Response bad = Http.as(bearer).delete("/wp-json/wp/v2/users/" + target + "?force=true&reassign=abc");
                bad.then()
                        .statusCode(400)
                        .body(equalTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): reassign\","
                                + "\"data\":{\"status\":400,\"params\":{\"reassign\":\"Invalid user parameter(s).\"},"
                                + "\"details\":{\"reassign\":{\"code\":\"rest_invalid_param\","
                                + "\"message\":\"Invalid user parameter(s).\",\"data\":{\"status\":400}}}}}"));
            }
        }
        Http.as(null)
                .delete("/wp-json/wp/v2/users/" + erin + "?force=true")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_missing_callback_param"))
                .body("message", equalTo("Missing parameter(s): reassign"));
        expect(
                Http.as("test-" + alice).delete("/wp-json/wp/v2/users/987654321?force=true&reassign=false"),
                404,
                "rest_user_invalid_id",
                "Invalid user ID.");
        // erin is still there
        Http.as("test-" + alice).get("/wp-json/wp/v2/users/" + erin).then().statusCode(200);
    }

    @Test
    void twoConcurrentPreferenceWritesBothSucceed() throws Exception {
        for (int round = 0; round < 10; round++) {
            List<Callable<Object>> writes = new ArrayList<>();
            for (int writer = 0; writer < 2; writer++) {
                String body = "{\"meta\":{\"persisted_preferences\":{\"core\":{\"round\":" + round + ",\"writer\":"
                        + writer + "}}}}";
                writes.add(() -> Http.as("test-" + dave)
                        .contentType("application/json")
                        .body(body)
                        .put("/wp-json/wp/v2/users/me")
                        .statusCode());
            }
            assertThat(Races.run(writes)).as("round " + round).containsExactly(200, 200);
        }
        JsonNode stored = json(Http.as("test-" + dave)
                .get("/wp-json/wp/v2/users/me?context=edit")
                .asString());
        assertThat(stored.at("/meta/persisted_preferences/core/round").intValue())
                .isEqualTo(9);
    }

    @Test
    void optionsDescribeWordPresssArguments() {
        JsonNode collection =
                json(Http.as("test-" + alice).options("/wp-json/wp/v2/users").asString());
        assertThat(fieldNames(collection.at("/endpoints/0/args")))
                .containsExactly(
                        "context",
                        "page",
                        "per_page",
                        "search",
                        "exclude",
                        "include",
                        "offset",
                        "order",
                        "orderby",
                        "slug",
                        "roles",
                        "capabilities",
                        "who",
                        "has_published_posts",
                        "search_columns");
        assertThat(collection.at("/endpoints/0/args/has_published_posts").toString())
                .isEqualTo("{\"description\":\"Limit result set to users who have published posts.\","
                        + "\"type\":[\"boolean\",\"array\"],\"items\":{\"type\":\"string\",\"enum\":{\"post\":\"post\","
                        + "\"page\":\"page\",\"attachment\":\"attachment\",\"wp_block\":\"wp_block\"}},"
                        + "\"required\":false}");
        assertThat(fieldNames(collection.at("/endpoints/1/args")))
                .containsExactly(
                        "username",
                        "name",
                        "first_name",
                        "last_name",
                        "email",
                        "url",
                        "description",
                        "locale",
                        "nickname",
                        "slug",
                        "roles",
                        "password",
                        "meta");
        assertThat(fieldNames(collection.at("/schema/properties")))
                .containsExactly(
                        "id",
                        "username",
                        "name",
                        "first_name",
                        "last_name",
                        "email",
                        "url",
                        "description",
                        "link",
                        "locale",
                        "nickname",
                        "slug",
                        "registered_date",
                        "roles",
                        "password",
                        "capabilities",
                        "extra_capabilities",
                        "avatar_urls",
                        "meta");
        JsonNode me =
                json(Http.as("test-" + alice).options("/wp-json/wp/v2/users/me").asString());
        assertThat(me.at("/endpoints/0").has("allow_batch")).isFalse();
        assertThat(fieldNames(me.at("/endpoints/2/args"))).containsExactly("force", "reassign");
    }

    private static void expect(Response response, int status, String code, String message) {
        response.then().statusCode(status).body("code", equalTo(code)).body("message", equalTo(message));
        assertThat(json(response.asString()).at("/data/status").intValue()).isEqualTo(status);
    }

    private long member(AgroalDataSource data, String name, String displayName, String role) {
        UserId user = TestData.profile(data, name + u, displayName, name + "@quarkpress.test");
        TestData.member(data, TestData.MAIN, user, "[\"" + role + "\"]");
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

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
