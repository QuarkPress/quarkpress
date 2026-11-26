package org.quarkword.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.restwp.runtime.wpv2.PreviewSessionController;

/**
 * The preview session route {@code /quarkword/v1/preview/session} on the main site ({@code localhost}): the cookie's
 * name and attributes, who may open and close a session, the cookie scoped to the site's path, and the REST API that
 * never reads the cookie nor the preview parameters, nor shows the preview secret. The pages the cookie opens are the
 * front module's (its {@code PreviewDbTest}). Members of the main site: the administrator alice, the author carol and
 * the subscriber erin; dave has a profile and no membership.
 */
class PreviewSessionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PreviewSessionDbTest.class, PostFixtures.class, PostFixtures.Builder.class, TestClock.class)
            .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true")
            .overrideConfigKey("quarkus.http.proxy.allow-x-forwarded", "true");

    /** The default of {@code quarkword.posts.preview-lifetime}, 12 hours, in seconds. */
    private static final int LIFETIME = 43200;

    private static final String SESSION = "/wp-json/quarkword/v1/preview/session";

    private static final String COOKIE = "quarkword_preview";

    private static final String SECRET_OPTION = "quarkword_preview_secret";

    static AgroalDataSource data;
    static SiteId site = TestData.MAIN;
    static long alice;
    static long carol;
    static long erin;
    static long dave;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        alice = member("alice", "Alice Admin", "alice@example.com", "administrator");
        carol = member("carol", "Carol Chen", "carol@example.com", "author");
        erin = member("erin", "Erin Evans", "erin@example.com", "subscriber");
        dave = TestData.profile(data, "dave", "Dave Dunn", "dave@example.com").value();
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void theCookieNameIsTheFrontEndsOne() {
        assertThat(PreviewSessionController.COOKIE).isEqualTo(COOKIE);
    }

    @Test
    void theSessionCookieIsScopedAndRevocable() {
        Response session = as(carol).post(SESSION);
        session.then()
                .statusCode(200)
                .header(
                        "Set-Cookie",
                        allOf(
                                startsWith(COOKIE + "="),
                                containsString("Max-Age=" + LIFETIME),
                                containsString("Path=/"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                not(containsString("Secure"))))
                .body("expires_in", equalTo(LIFETIME));
        assertThat(session.getCookie(COOKIE)).hasSize(43);
        // behind a trusted proxy, an HTTPS request's cookie is Secure
        as(carol)
                .header("X-Forwarded-Proto", "https")
                .post(SESSION)
                .then()
                .statusCode(200)
                .header("Set-Cookie", containsString("; Secure"));
        // nobody, and a user who is no member of the site
        as(null).post(SESSION)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden"))
                .body("message", equalTo("Sorry, you are not allowed to do that."))
                .body("data.status", equalTo(401));
        as(dave).post(SESSION)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"))
                .body("data.status", equalTo(403));
        // a subscriber is a member: the session opens
        as(erin).post(SESSION).then().statusCode(200);
        // DELETE clears the cookie
        as(carol)
                .delete(SESSION)
                .then()
                .statusCode(200)
                .header(
                        "Set-Cookie",
                        allOf(
                                startsWith(COOKIE + "=;"),
                                containsString("Max-Age=0"),
                                containsString("Path=/"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax")))
                .body("closed", equalTo(true));
        // nobody cannot close anything
        as(null).delete(SESSION).then().statusCode(401).body("code", equalTo("rest_forbidden"));
    }

    @Test
    void theCookieIsScopedToItsSitePath() {
        String news = "news-" + UUID.randomUUID();
        Jdbc.update(data, "insert into qw_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        SiteId newsSite = SiteId.of(Jdbc.count(data, "select id from qw_site where path = ?", "/" + news + "/"));
        TestData.member(data, newsSite, UserId.of(carol), "[\"author\"]");
        as(carol)
                .post("/" + news + "/wp-json/quarkword/v1/preview/session")
                .then()
                .statusCode(200)
                .header("Set-Cookie", containsString("Path=/" + news + "/;"));
    }

    @Test
    void theCookieNeverAuthenticatesRest() {
        String carols = session(carol);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("Draft title")
                .publishedAt(null)
                .insert(data);
        page(carols).get("/wp-json/wp/v2/users/me").then().statusCode(401).body("code", equalTo("rest_not_logged_in"));
        // a status only an editor may ask for: the cookie gets the anonymous refusal, not carol's list
        Response anonymous = as(null).get("/wp-json/wp/v2/posts?status=draft");
        assertThat(anonymous.statusCode()).isNotEqualTo(200);
        page(carols)
                .get("/wp-json/wp/v2/posts?status=draft")
                .then()
                .statusCode(anonymous.statusCode())
                .body("code", equalTo(anonymous.jsonPath().getString("code")));
        as(carol).get("/wp-json/wp/v2/posts?status=draft").then().statusCode(200);
        page(carols)
                .get("/?rest_route=/wp/v2/users/me&p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_not_logged_in"));
    }

    @Test
    void restIgnoresPreviewParameters() {
        as(null).get("/wp-json/wp/v2/posts?preview_id=1&preview_nonce=bad")
                .then()
                .statusCode(200);
        as(carol)
                .get("/wp-json/wp/v2/posts?preview_id=1&preview_nonce=bad&preview=true")
                .then()
                .statusCode(200);
    }

    @Test
    void thePreviewSecretIsNoSetting() {
        Jdbc.option(site, SECRET_OPTION, "\"c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==\"");
        Response settings = as(alice).get("/wp-json/wp/v2/settings");
        settings.then().statusCode(200);
        assertThat(settings.asString()).doesNotContain(SECRET_OPTION);
        assertThat(as(alice).get("/wp-json/wp/v2/settings?context=edit").asString())
                .doesNotContain(SECRET_OPTION);
    }

    /**
     * Without the front module, the plain links follow an https request as WordPress's {@code home_url},
     * {@code site_url} and {@code admin_url} do under {@code is_ssl()}; the index's {@code url} stays the stored
     * address. The request is https through this application's trusted proxy header.
     */
    @Test
    void plainLinksFollowAnHttpsRequestWithoutTheFrontModule() {
        PostId post = PostFixtures.published(data, site, UserId.of(carol), "Secure title", "2026-01-11T10:00:00Z");
        as(null).header("X-Forwarded-Proto", "https")
                .get("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200)
                .body("link", equalTo("https://localhost/?p=" + post.value()));
        as(null).header("X-Forwarded-Proto", "https")
                .get("/wp-json/")
                .then()
                .statusCode(200)
                .body("home", equalTo("https://localhost"))
                .body("url", equalTo("http://localhost"));
        as(carol)
                .header("X-Forwarded-Proto", "https")
                .get("/wp-json/quarkword/v1/editor/post/" + post.value())
                .then()
                .statusCode(200)
                .body("settings.__experimentalDashboardLink", equalTo("https://localhost/wp-admin/"));
        as(alice)
                .header("X-Forwarded-Proto", "https")
                .get("/wp-json/wp/v2/themes?status=active")
                .then()
                .statusCode(200)
                .body("[0].stylesheet_uri", equalTo("https://localhost/wp-content/themes/quarkword"));
        // a plain http request keeps http
        as(null).get("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200)
                .body("link", equalTo("http://localhost/?p=" + post.value()));
        as(alice)
                .get("/wp-json/wp/v2/themes?status=active")
                .then()
                .statusCode(200)
                .body("[0].stylesheet_uri", equalTo("http://localhost/wp-content/themes/quarkword"));
    }

    // ---- helpers

    /** A REST request to the main site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.as(user == null ? null : "test-" + user);
    }

    /** A request to the main site with the session cookie. */
    private static RequestSpecification page(String cookie) {
        return Http.as(null).cookie(COOKIE, cookie);
    }

    /** A fresh session token of the user on the main site. */
    private static String session(long user) {
        Response opened = as(user).post(SESSION);
        opened.then().statusCode(200);
        return opened.getCookie(COOKIE);
    }
}
