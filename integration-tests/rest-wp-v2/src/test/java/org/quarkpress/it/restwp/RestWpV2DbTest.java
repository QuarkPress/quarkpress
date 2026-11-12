package org.quarkpress.it.restwp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The REST API (wp/v2) end to end, with real Keycloak tokens: JVM mode here, the packaged application in
 * {@code RestWpV2IT}, and a native executable in CI. Every test runs on a network (the multisite install): the main
 * site is {@code localhost} at {@code /}, alice is a network administrator; two tests add a site in a subdirectory.
 * The content tests run the editor's flow (a new draft, its edit lock, autosaves, the preview session and the minimal
 * front-end page), the posts, pages and books routes (writes, meta, term filters, links and embeds, revisions, search)
 * and the media routes (uploads read, served, processed and deleted, the collection's filters, a featured image
 * embedded), so that the native run in CI proves their runtime paths too.
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
                .body("namespaces", contains("oembed/1.0", "wp/v2", "quarkpress/v1"))
                .body("routes", hasKey("/quarkpress/v1/drafts"))
                .body("routes", hasKey("/wp/v2/search"))
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
        as(null).get("/wp-json/wp/v2/search?type=term&per_page=1")
                .then()
                .statusCode(200)
                .header("Allow", "GET");
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
    void locksAndOwnSitesThroughTheSetupResourceNeedManageOptions() {
        // the setup resource's system writes are reachable for a site administrator only (carol is an author)
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId("carol"), "age", 0))
                .put("/it/locks/1")
                .then()
                .statusCode(403);
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId("carol"), "path", "/" + token() + "/", "title", "own"))
                .post("/it/own-sites")
                .then()
                .statusCode(403);
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
        // its front end: the site path is looked up off the event loop, and the post is this site's alone
        String postTitle = "Sub post " + token();
        int post = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("title", postTitle, "status", "publish", "content", "sub words"))
                .post(path + "wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        page(null)
                .get(path + "?p=" + post)
                .then()
                .statusCode(200)
                .contentType("text/html; charset=UTF-8")
                .body(containsString("<h1 class=\"entry-title\">" + postTitle + "</h1>"))
                .body(containsString("<p class=\"site-title\">" + title + "</p>"));
        page(null).get("/?p=" + post).then().statusCode(404);
    }

    private static final String PREVIEW_COOKIE = "quarkpress_preview";

    private static final String BLOCK = "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->";

    /** The request on the site's front end with the preview session cookie, or without one. */
    private RequestSpecification page(String cookie) {
        RequestSpecification request =
                given().header("Host", "localhost").redirects().follow(false);
        return cookie == null ? request : request.cookie(PREVIEW_COOKIE, cookie);
    }

    /** Opens a preview session for {@code user}; answers the cookie's value. */
    private String previewSession(String user) {
        Response session = as(user).post("/wp-json/quarkpress/v1/preview/session");
        session.then()
                .statusCode(200)
                .header("Set-Cookie", allOf(startsWith(PREVIEW_COOKIE + "="), containsString("Path=/")))
                .body("expires_in", equalTo(43200));
        return session.getCookie(PREVIEW_COOKIE);
    }

    /** The path and query of an absolute link on the main site. */
    private static String local(String link) {
        assertThat(link).startsWith("http://localhost/");
        return link.substring("http://localhost".length());
    }

    @Test
    void theEditorsFlowFromNewPostToPreview() {
        // main site of the network: carol (author) opens a new post as the block editor does, bob (editor) arrives
        Response draft = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("type", "post"))
                .post("/wp-json/quarkpress/v1/drafts");
        draft.then()
                .statusCode(201)
                .body("status", equalTo("auto-draft"))
                .body("title.raw", equalTo("Auto Draft"))
                .body("author", equalTo((int) userId("carol")));
        int id = draft.path("id");
        assertThat(draft.getHeader("Location")).isEqualTo("http://localhost/wp-json/wp/v2/posts/" + id);

        Response lock = as("carol").post("/wp-json/quarkpress/v1/locks/" + id);
        lock.then()
                .statusCode(200)
                .body("isLocked", equalTo(false))
                .body("activePostLock", endsWith(":" + userId("carol")));
        assertThat(lock.jsonPath().getString("release_token")).hasSize(43);

        // the lock holder's autosave of her own draft updates the post itself
        as("carol")
                .contentType(ContentType.JSON)
                .body("{\"title\":\"Flow\",\"content\":\"" + BLOCK.replace("\"", "\\\"")
                        + "\",\"status\":\"draft\",\"meta\":{\"footnotes\":\"\"}}")
                .post("/wp-json/wp/v2/posts/" + id + "/autosaves")
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("title.raw", equalTo("Flow"))
                .body("content.raw", equalTo(BLOCK));

        // the preview opens with the session cookie only; without it, the draft does not exist
        String cookie = previewSession("carol");
        page(cookie)
                .get("/?p=" + id + "&preview=true")
                .then()
                .statusCode(200)
                .contentType("text/html; charset=UTF-8")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0")
                .body(allOf(startsWith("<!DOCTYPE html>"), containsString("<h1 class=\"entry-title\">Flow</h1>")));
        page(null).get("/?p=" + id + "&preview=true").then().statusCode(404);

        // bob finds the post locked by carol and nothing is taken
        as("bob")
                .post("/wp-json/quarkpress/v1/locks/" + id)
                .then()
                .statusCode(200)
                .body("isLocked", equalTo(true))
                .body("user.name", equalTo("Carol Chen"))
                .body("user.avatar", startsWith("https://secure.gravatar.com/avatar/"));

        // carol's heartbeat refreshes the lock; the tab's beacon releases it with the release token alone
        Response refreshed = as("carol").put("/wp-json/quarkpress/v1/locks/" + id);
        refreshed.then().statusCode(200).body("new_lock", endsWith(":" + userId("carol")));
        as(null).contentType(ContentType.JSON)
                .body(Map.of(
                        "active_post_lock",
                        refreshed.jsonPath().getString("new_lock"),
                        "release_token",
                        refreshed.jsonPath().getString("release_token")))
                .post("/wp-json/quarkpress/v1/locks/" + id + "?_method=DELETE")
                .then()
                .statusCode(200)
                .body("released", equalTo(true));

        // bob takes over; carol's next heartbeat names him
        as("bob")
                .post("/wp-json/quarkpress/v1/locks/" + id + "/takeover")
                .then()
                .statusCode(200)
                .body("isLocked", equalTo(false))
                .body("activePostLock", endsWith(":" + userId("bob")));
        as("carol")
                .put("/wp-json/quarkpress/v1/locks/" + id)
                .then()
                .statusCode(200)
                .body("lock_error.name", equalTo("Bob Baker"))
                .body("lock_error.text", equalTo("Bob Baker has taken over and is currently editing."));

        // closing the session closes the preview
        as("carol")
                .delete("/wp-json/quarkpress/v1/preview/session")
                .then()
                .statusCode(200)
                .body("closed", equalTo(true));
        page(cookie).get("/?p=" + id + "&preview=true").then().statusCode(404);

        // a contributor may not open a page; nobody may open a draft
        as(null).contentType(ContentType.JSON)
                .body(Map.of("type", "post"))
                .post("/wp-json/quarkpress/v1/drafts")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create posts as this user."));
        as("dave")
                .contentType(ContentType.JSON)
                .body(Map.of("type", "page"))
                .post("/wp-json/quarkpress/v1/drafts")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create posts as this user."));
    }

    @Test
    void anAutosaveOfAPublishedPostIsPreviewedThroughItsLink() {
        // main site of the network: carol's published post keeps its words; her autosave is shown in the preview
        String title = "Published " + token();
        int id = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", title, "content", BLOCK, "status", "publish"))
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        Response autosave = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Autosaved " + title, "content", BLOCK))
                .post("/wp-json/wp/v2/posts/" + id + "/autosaves");
        autosave.then()
                .statusCode(200)
                .body("parent", equalTo(id))
                .body("slug", equalTo(id + "-autosave-v1"))
                .body("title.raw", equalTo("Autosaved " + title))
                .body("preview_link", containsString("preview_nonce="));
        int autosaveId = autosave.path("id");
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id + "/autosaves?context=edit")
                .then()
                .statusCode(200)
                .body("id", contains(autosaveId))
                .body("[0].preview_link", containsString("preview_id=" + id));
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id + "/autosaves/" + autosaveId)
                .then()
                .statusCode(200)
                .body("id", equalTo(autosaveId));
        as("erin")
                .get("/wp-json/wp/v2/posts/" + id + "/autosaves")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_read"))
                .body("message", equalTo("Sorry, you are not allowed to view autosaves of this post."));
        as("carol").get("/wp-json/wp/v2/posts/" + id).then().statusCode(200).body("title.rendered", equalTo(title));

        // the preview link (a computed token) arms the autosave's overlay for carol's session
        String link = local(autosave.path("preview_link"));
        String cookie = previewSession("carol");
        page(cookie)
                .get(link)
                .then()
                .statusCode(200)
                .body(containsString("<h1 class=\"entry-title\">Autosaved " + title + "</h1>"));
        // another viewer with carol's token is refused, as HTML and as JSON
        page(previewSession("bob"))
                .get(link)
                .then()
                .statusCode(403)
                .contentType("text/html; charset=UTF-8")
                .body(containsString("Sorry, you are not allowed to preview drafts."));
        page(null)
                .header("Accept", "application/json")
                .get(link)
                .then()
                .statusCode(403)
                .contentType("application/json; charset=UTF-8")
                .body("code", equalTo("wp_die"))
                .body("message", equalTo("Sorry, you are not allowed to preview drafts."));
        // the published post itself, and its canonical redirect when asked for as a bare preview
        page(null)
                .get("/?p=" + id)
                .then()
                .statusCode(200)
                .body(containsString("<h1 class=\"entry-title\">" + title + "</h1>"));
        page(null)
                .get("/?p=" + id + "&preview=true")
                .then()
                .statusCode(301)
                .header("Location", "http://localhost/?p=" + id);
    }

    @Test
    void theFrontEndPageShowsAPublishedPostWithItsDate() {
        // main site of the network: the human date is English month names (also in a native executable)
        String title = "Dated " + token();
        long id = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "type",
                        "post",
                        "status",
                        "publish",
                        "title",
                        title,
                        "fields",
                        Map.of("content", "Body words", "date_gmt", "2026-01-10T12:00:00")))
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        page(null)
                .get("/index.php?p=" + id)
                .then()
                .statusCode(200)
                .contentType("text/html; charset=UTF-8")
                .body(allOf(
                        containsString("<title>" + title + " &#8211; "),
                        containsString("January 10, 2026"),
                        containsString("Body words")));
        page(null).head("/?p=" + id).then().statusCode(200);
        page(null).get("/?p=" + (id + 1_000_000)).then().statusCode(404);
    }

    @Test
    void aPostIsWrittenReadFilteredRevisedAndDeleted() {
        // main site of the network: carol (author) writes, bob made the category, nobody reads
        int category = category(token(), null);
        int tag = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("name", token()))
                .post("/wp-json/wp/v2/tags")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        String word = token();
        String title = "Post " + word;
        Response created = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "title",
                        title,
                        "content",
                        BLOCK,
                        "status",
                        "publish",
                        "categories",
                        List.of(category),
                        "tags",
                        List.of(tag),
                        "meta",
                        Map.of("footnotes", "")))
                .post("/wp-json/wp/v2/posts");
        created.then()
                .statusCode(201)
                .body("status", equalTo("publish"))
                .body("author", equalTo((int) userId("carol")))
                .body("categories", contains(category))
                .body("tags", contains(tag))
                .body("content.block_version", equalTo(1))
                .body("meta.footnotes", equalTo(""));
        int id = created.path("id");
        assertThat(created.getHeader("Location")).isEqualTo("http://localhost/wp-json/wp/v2/posts/" + id);

        // the item, its links and embeds
        as(null).get("/wp-json/wp/v2/posts/" + id + "?_embed")
                .then()
                .statusCode(200)
                .body("title.rendered", equalTo(title))
                .body("link", equalTo("http://localhost/?p=" + id))
                .body("content.rendered", containsString("<p>x</p>"))
                .body("_links.self[0].href", equalTo("http://localhost/wp-json/wp/v2/posts/" + id))
                .body("_links.about[0].href", equalTo("http://localhost/wp-json/wp/v2/types/post"))
                .body("_embedded.author[0].slug", equalTo("carol"))
                .body("_embedded.'wp:term'[0][0].id", equalTo(category))
                .body("_embedded.'wp:term'[1][0].id", equalTo(tag));
        JsonPath fields = as(null).get("/wp-json/wp/v2/posts/" + id + "?_fields=id,title")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(fields.getMap("$")).containsOnlyKeys("id", "title");

        // the collection, its search and term filters
        as(null).get("/wp-json/wp/v2/posts?search=" + word)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .header("X-WP-TotalPages", "1")
                .body("id", contains(id));
        as(null).get("/wp-json/wp/v2/posts?categories=" + category)
                .then()
                .statusCode(200)
                .body("id", contains(id));
        as(null).get("/wp-json/wp/v2/posts?tags=" + tag + "&categories_exclude=" + category)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body("$", empty());
        as(null).get("/wp-json/wp/v2/posts?status=draft")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): status"))
                .body("data.params.status", equalTo("Status is forbidden."));

        // an update makes a revision; carol reads the history, nobody does not, nobody deletes a revision
        String revised = "Revised " + word;
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", revised))
                .put("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200)
                .body("title.raw", equalTo(revised));
        Response history = as("carol").get("/wp-json/wp/v2/posts/" + id + "/revisions?context=edit");
        history.then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].parent", equalTo(id))
                .body("[0].title.raw", equalTo(revised));
        int revision = history.path("[0].id");
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id + "/revisions/" + revision)
                .then()
                .statusCode(200)
                .body("id", equalTo(revision))
                .body("_links.parent[0].href", equalTo("http://localhost/wp-json/wp/v2/posts/" + id));
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200)
                .body("_links.'version-history'[0].count", greaterThanOrEqualTo(1));
        as(null).get("/wp-json/wp/v2/posts/" + id + "/revisions?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_read"))
                .body("message", equalTo("Sorry, you are not allowed to view revisions of this post."));
        as("carol")
                .delete("/wp-json/wp/v2/posts/" + id + "/revisions/" + revision + "?force=true")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo("Sorry, you are not allowed to delete this revision."));

        // another author may not edit it
        as("dave")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "not mine"))
                .put("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));

        // trash, then delete for good
        as("carol")
                .delete("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("status", equalTo("trash"));
        as("carol")
                .delete("/wp-json/wp/v2/posts/" + id + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo(id));
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
    }

    @Test
    void pagesNestAndOnlyEditorsWriteThem() {
        // main site of the network: bob (editor) writes pages, carol (author) may not
        String word = token();
        int parent = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Parent " + word, "status", "publish"))
                .post("/wp-json/wp/v2/pages")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        int child = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Child " + word, "status", "publish", "parent", parent, "menu_order", 2))
                .post("/wp-json/wp/v2/pages")
                .then()
                .statusCode(201)
                .body("parent", equalTo(parent))
                .body("menu_order", equalTo(2))
                .extract()
                .path("id");
        as(null).get("/wp-json/wp/v2/pages/" + child + "?_embed")
                .then()
                .statusCode(200)
                .body("link", equalTo("http://localhost/?page_id=" + child))
                .body("_embedded.up[0].id", equalTo(parent));
        as(null).get("/wp-json/wp/v2/pages?parent=" + parent)
                .then()
                .statusCode(200)
                .body("id", contains(child));
        page(null)
                .get("/?page_id=" + child)
                .then()
                .statusCode(200)
                .body(containsString("<h1 class=\"entry-title\">Child " + word + "</h1>"));
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "no"))
                .post("/wp-json/wp/v2/pages")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo("Sorry, you are not allowed to create posts as this user."));
        as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Child revised " + word))
                .post("/wp-json/wp/v2/pages/" + child)
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("Child revised " + word));
        as("bob")
                .get("/wp-json/wp/v2/pages/" + child + "/revisions?context=edit")
                .then()
                .statusCode(200)
                .body("[0].title.raw", equalTo("Child revised " + word))
                .body("[0].excerpt.raw", equalTo(""));
        as("bob")
                .delete("/wp-json/wp/v2/pages/" + child + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));
        as(null).get("/wp-json/wp/v2/pages?parent=" + parent)
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void booksCarryTheirRegisteredMeta() {
        // main site of the network: the book type of the IT app and its two registered meta keys
        String word = token();
        Response created = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "title",
                        "Book " + word,
                        "slug",
                        "book-" + word,
                        "status",
                        "publish",
                        "meta",
                        Map.of("book_rating", 5, "book_tags", List.of("red", "blue"))))
                .post("/wp-json/wp/v2/books");
        created.then()
                .statusCode(201)
                .body("type", equalTo("book"))
                .body("meta.book_rating", equalTo(5))
                .body("meta.book_tags", contains("red", "blue"));
        int id = created.path("id");
        as(null).get("/wp-json/wp/v2/books/" + id)
                .then()
                .statusCode(200)
                .body("link", equalTo("http://localhost/?book=book-" + word))
                .body("meta.book_rating", equalTo(5))
                .body("meta.book_tags", contains("red", "blue"));
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("meta", Map.of("book_rating", 4, "book_tags", List.of("green"))))
                .put("/wp-json/wp/v2/books/" + id)
                .then()
                .statusCode(200)
                .body("meta.book_rating", equalTo(4))
                .body("meta.book_tags", contains("green"));
        as(null).get("/wp-json/wp/v2/books?search=" + word)
                .then()
                .statusCode(200)
                .body("id", contains(id));
        // a book without a stored rating answers the registered default
        int unrated = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Unrated " + word, "status", "draft"))
                .post("/wp-json/wp/v2/books")
                .then()
                .statusCode(201)
                .body("meta.book_rating", equalTo(3))
                .body("meta.book_tags", empty())
                .extract()
                .path("id");
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Autosaved unrated " + word))
                .post("/wp-json/wp/v2/books/" + unrated + "/autosaves")
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("Autosaved unrated " + word));
        as("carol")
                .options("/wp-json/wp/v2/books/" + id + "/autosaves")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST");
    }

    @Test
    void collectionsSearchAndRevisionsAnswer() {
        // main site of the network: every collection answers, as anybody and as the item's author in edit context
        String word = token();
        int id = as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Searched " + word, "content", "first", "status", "publish"))
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("content", "second"))
                .put("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200);
        as("carol")
                .get("/wp-json/wp/v2/posts?context=edit&search=" + word)
                .then()
                .statusCode(200)
                .body("id", contains(id))
                .body("[0].content.raw", equalTo("second"));
        // a page by bob (carol may not write pages) and a book by carol, each found by its own word
        for (List<String> writer : List.of(List.of("pages", "bob", "page"), List.of("books", "carol", "book"))) {
            String collection = writer.get(0);
            String own = token();
            int item = as(writer.get(1))
                    .contentType(ContentType.JSON)
                    .body(Map.of("title", "Listed " + own, "status", "publish"))
                    .post("/wp-json/wp/v2/" + collection)
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("id");
            as(null).get("/wp-json/wp/v2/" + collection + "?search=" + own)
                    .then()
                    .statusCode(200)
                    .header("X-WP-Total", "1")
                    .body("id", contains(item))
                    .body("[0].type", equalTo(writer.get(2)))
                    .body("[0].title.rendered", equalTo("Listed " + own));
            as(writer.get(1))
                    .get("/wp-json/wp/v2/" + collection + "?context=edit&search=" + own)
                    .then()
                    .statusCode(200)
                    .body("id", contains(item))
                    .body("[0].title.raw", equalTo("Listed " + own));
        }
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id + "/revisions?context=edit")
                .then()
                .statusCode(200)
                .body("[0].content.raw", equalTo("second"));
        as("carol")
                .get("/wp-json/wp/v2/posts/" + id + "/revisions?per_page=1&page=99")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_revision_invalid_page_number"))
                .body("message", equalTo("The page number requested is larger than the number of pages available."));
        as(null).get("/wp-json/wp/v2/search?search=" + word)
                .then()
                .statusCode(200)
                .body("id", contains(id))
                .body("[0].type", equalTo("post"))
                .body("[0].subtype", equalTo("post"))
                .body("[0].url", equalTo("http://localhost/?p=" + id))
                .body("[0]._links.self[0].href", equalTo("http://localhost/wp-json/wp/v2/posts/" + id));
        as(null).get("/wp-json/wp/v2/search?search=" + word + "&_embed")
                .then()
                .statusCode(200)
                .body("[0]._embedded.self[0].id", equalTo(id));
        String name = "Term " + word;
        int category = category(name, null);
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + word)
                .then()
                .statusCode(200)
                .body("id", contains(category))
                .body("[0].title", equalTo(name))
                .body("[0].type", equalTo("category"));
        // every REST taxonomy without a search: the total counts every term, whatever other tests created
        Response terms = as(null).get("/wp-json/wp/v2/search?type=term&per_page=1");
        terms.then().statusCode(200).body("$", hasSize(1));
        assertThat(Integer.parseInt(terms.getHeader("X-WP-Total"))).isGreaterThanOrEqualTo(2);
        as(null).get("/wp-json/wp/v2/search?type=term&subtype=category&search=" + word)
                .then()
                .statusCode(200)
                .body("id", contains(category));
        // the post-format handler lists a format once a post carries it
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "type",
                        "post",
                        "status",
                        "publish",
                        "title",
                        "Aside " + word,
                        "fields",
                        Map.of("format", "aside")))
                .post("/it/posts")
                .then()
                .statusCode(200);
        as(null).get("/wp-json/wp/v2/search?type=post-format&search=aside")
                .then()
                .statusCode(200)
                .body("id", contains("aside"))
                .body("[0].title", equalTo("Aside"))
                .body("[0].url", equalTo("http://localhost/?post_format=aside"))
                .body("[0].type", equalTo("post-format"));
        as(null).get("/wp-json/wp/v2/search?type=nope")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): type"));
    }

    private static final String MEDIA = "/wp-json/wp/v2/media";

    /** Uploads {@code bytes} as {@code name} in a multipart form to the site at {@code sitePath}, as {@code user}. */
    private Response upload(String user, String sitePath, String name, String type, byte[] bytes, String... fields) {
        RequestSpecification request = as(user).multiPart("file", name, bytes, type);
        for (int i = 0; i < fields.length; i += 2) {
            request.multiPart(fields[i], fields[i + 1]);
        }
        return request.post(sitePath + MEDIA.substring(1));
    }

    /** The id of a 201 upload answer, checked against its {@code X-WP-Upload-Attachment-ID} header. */
    private static int created(Response answer) {
        answer.then().statusCode(201);
        int id = answer.path("id");
        assertThat(answer.header("X-WP-Upload-Attachment-ID")).isEqualTo(Integer.toString(id));
        return id;
    }

    @Test
    void anUploadIsReadProcessedAndDeleted() {
        // main site of the network: carol (author) uploads a 1200x800 JPEG, its sizes made while uploading
        Response jpeg = upload("carol", "/", "IMG_1234.jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800));
        int id = created(jpeg);
        assertThat(jpeg.header("Location")).isEqualTo("http://localhost/wp-json/wp/v2/media/" + id);
        JsonPath read = as(null).get(MEDIA + "/" + id)
                .then()
                .statusCode(200)
                .body("media_type", equalTo("image"))
                .body("mime_type", equalTo("image/jpeg"))
                .body("media_details.width", equalTo(1200))
                .body("media_details.height", equalTo(800))
                .body("media_details.sizes.medium.width", equalTo(300))
                .body("media_details.sizes.medium.height", equalTo(200))
                .extract()
                .jsonPath();
        String source = read.getString("source_url");
        String medium = read.getString("media_details.sizes.medium.source_url");
        assertThat(source).contains("/wp-content/uploads/").contains("IMG_1234").endsWith(".jpg");
        assertThat(medium).contains("/wp-content/uploads/").endsWith("-300x200.jpg");
        // the uploads route serves the files
        page(null).get(local(medium)).then().statusCode(200).contentType("image/jpeg");
        page(null).get(local(source)).then().statusCode(200).contentType("image/jpeg");
        // a raw PNG body named by its Content-Disposition, its sizes left for the post-process
        Response png = as("carol")
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=\"raw-" + token() + ".png\"")
                .body(MediaFixtures.png(400, 300))
                .post(MEDIA + "?generate_sub_sizes=false");
        int pngId = created(png);
        assertThat(png.<Map<String, Object>>path("media_details.sizes")).doesNotContainKey("thumbnail");
        as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("action", "create-image-subsizes"))
                .post(MEDIA + "/" + pngId + "/post-process")
                .then()
                .statusCode(200)
                .body("id", equalTo(pngId))
                .body("media_details.sizes.thumbnail.width", equalTo(150))
                .body("media_details.sizes.medium.width", equalTo(300))
                .body("missing_image_sizes", empty());
        // the JPEG deleted for good: the item and its files are gone
        as("carol")
                .delete(MEDIA + "/" + id + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo(id))
                .body("previous.source_url", equalTo(source));
        as("carol")
                .get(MEDIA + "/" + id)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        page(null).get(local(source)).then().statusCode(404);
        page(null).get(local(medium)).then().statusCode(404);
    }

    @Test
    void theCollectionFiltersByTypeAndSearchesPaths() {
        // main site of the network: a JPEG and a PDF under the same file name, their titles naming neither
        String stem = "IMG_12" + token();
        int jpeg = created(
                upload("carol", "/", stem + ".jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), "title", "Holiday"));
        int pdf =
                created(upload("carol", "/", stem + ".pdf", "application/pdf", MediaFixtures.pdf(), "title", "Notes"));
        // the search reaches the stored path, not only the title
        as(null).get(MEDIA + "?search=" + stem)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "2")
                .body("id", containsInAnyOrder(jpeg, pdf));
        as(null).get(MEDIA + "?media_type=image&search=" + stem)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("id", contains(jpeg))
                .body("[0].title.rendered", equalTo("Holiday"));
        as(null).get(MEDIA + "?media_type=application&search=" + stem)
                .then()
                .statusCode(200)
                .body("id", contains(pdf));
    }

    @Test
    void aPostsFeaturedImageIsEmbedded() {
        // main site of the network: bob (editor) publishes a post whose featured image is carol's JPEG
        int image = created(upload("carol", "/", "f" + token() + ".jpg", "image/jpeg", MediaFixtures.jpeg(64, 48)));
        int post = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Featured " + token(), "status", "publish", "featured_media", image))
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .body("featured_media", equalTo(image))
                .extract()
                .path("id");
        as(null).get("/wp-json/wp/v2/posts/" + post + "?_embed")
                .then()
                .statusCode(200)
                .body("_links.'wp:featuredmedia'[0].href", equalTo("http://localhost/wp-json/wp/v2/media/" + image))
                .body("_links.'wp:featuredmedia'[0].embeddable", equalTo(true))
                .body("_embedded.'wp:featuredmedia'[0].id", equalTo(image))
                .body("_embedded.'wp:featuredmedia'[0].media_type", equalTo("image"));
    }

    @Test
    void aSubdirectorySiteUploadsUnderItsPrefix() {
        // a network site in a subdirectory of localhost, created by alice (network administrator), who uploads to it
        String path = "/m-" + token() + "/";
        int site = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", "Media " + token()))
                .post("/it/sites")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Response answer = upload("alice", path, "sub.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48));
        int id = created(answer);
        assertThat(answer.header("Location")).isEqualTo("http://localhost" + path + "wp-json/wp/v2/media/" + id);
        String source = answer.path("source_url");
        assertThat(source).startsWith("http://localhost" + path + "wp-content/uploads/sites/" + site + "/");
        page(null).get(local(source)).then().statusCode(200).contentType("image/jpeg");
        // the site's own collection holds it, the main site's does not
        as("alice")
                .get(path + MEDIA.substring(1) + "?per_page=100")
                .then()
                .statusCode(200)
                .body("id", contains(id));
        as("alice").get(MEDIA + "/" + id).then().statusCode(404);
    }
}
