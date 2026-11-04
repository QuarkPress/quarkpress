package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The preview session route {@code /quarkpress/v1/preview/session} and the minimal front-end page on the main site
 * ({@code localhost}): the cookie's attributes and revocation, who sees a draft, the nonce pair's 403 as HTML and as
 * JSON, the canonical redirect of a published post asked for as a preview, the overlay of the latest autosave of any
 * user, the password notice, and the credentials that never open a preview (another post's token, a demoted user, an
 * expired session, the cookie on a REST URL, the cookie of another site's path, another site's post). Members of the
 * main site: the administrator alice, the editor bob, the author carol and the subscriber erin; dave has a profile and
 * no membership; the clock is the test's.
 */
class PreviewDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PreviewDbTest.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class,
                    TestClock.class,
                    LedgerType.class)
            .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true")
            .overrideConfigKey("quarkus.http.proxy.allow-x-forwarded", "true");

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    /** The default of {@code quarkpress.posts.preview-lifetime}, 12 hours, in seconds. */
    private static final int LIFETIME = 43200;

    private static final String SESSION = "/wp-json/quarkpress/v1/preview/session";

    private static final String COOKIE = "quarkpress_preview";

    private static final String CANNOT_PREVIEW = "Sorry, you are not allowed to preview drafts.";

    private static final String NOTICE =
            "This content is password protected. To view it please enter your password below:";

    private static final String HTML = "text/html; charset=UTF-8";

    private static final String SECRET_OPTION = "quarkpress_preview_secret";

    static AgroalDataSource data;
    static SiteId site = TestData.MAIN;
    static long alice;
    static long bob;
    static long carol;
    static long erin;
    static long dave;
    static String siteName;

    @Inject
    TestClock clock;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        alice = member("alice", "Alice Admin", "alice@example.com", "administrator");
        bob = member("bob", "Bob Baker", "bob@example.com", "editor");
        carol = member("carol", "Carol Chen", "carol@example.com", "author");
        erin = member("erin", "Erin Evans", "erin@example.com", "subscriber");
        dave = TestData.profile(data, "dave", "Dave Dunn", "dave@example.com").value();
        String stored = Jdbc.optionJson(site, "blogname");
        siteName = stored == null ? "" : stored.substring(1, stored.length() - 1);
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void theSessionCookieIsScopedAndRevocable() {
        clock.set(NOW);
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
        String token = session.getCookie(COOKIE);
        assertThat(token).hasSize(43);
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
        // a subscriber is a member: the session opens, it just shows nothing more than anyone sees
        as(erin).post(SESSION).then().statusCode(200);

        // the cookie opens the viewer's draft; a second session too; DELETE revokes both
        PostId draft = draftOf(carol, "Session draft");
        String second = as(carol).post(SESSION).getCookie(COOKIE);
        page(token).get("/?p=" + draft.value() + "&preview=true").then().statusCode(200);
        page(second).get("/?p=" + draft.value() + "&preview=true").then().statusCode(200);
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
        page(token).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        page(second).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // nobody cannot close anything
        as(null).delete(SESSION).then().statusCode(401).body("code", equalTo("rest_forbidden"));
    }

    @Test
    void aProtectedPostShowsNoContentWithoutEditRights() {
        clock.set(NOW);
        PostId locked = PostFixtures.builder(site)
                .author(bob)
                .title("Locked title")
                .content("SECRET")
                .password("s3")
                .insert(data);
        for (String cookie : new String[] {null, session(erin), session(carol)}) {
            page(cookie)
                    .get("/?p=" + locked.value())
                    .then()
                    .statusCode(200)
                    .contentType(HTML)
                    .body(allOf(
                            containsString("<h1 class=\"entry-title\">Locked title</h1>"),
                            containsString(NOTICE),
                            not(containsString("SECRET"))));
        }
        // the author previewing her own protected draft is lifted: preview mode and edit_post
        PostId own = PostFixtures.builder(site)
                .author(carol)
                .status("draft")
                .publishedAt(null)
                .title("Own locked draft")
                .content("SECRET")
                .password("s3")
                .insert(data);
        page(session(carol))
                .get("/?p=" + own.value() + "&preview=true")
                .then()
                .statusCode(200)
                .body(allOf(containsString("SECRET"), not(containsString(NOTICE))));
        page(session(erin)).get("/?p=" + own.value() + "&preview=true").then().statusCode(404);
    }

    @Test
    void draftsNeedAViewerWhoMayEditThem() {
        clock.set(NOW);
        PostId draft = draftOf(carol, "Draft title");
        page(null)
                .get("/?p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(404)
                .contentType(HTML);
        page(null).get("/?p=" + draft.value()).then().statusCode(404);
        String carols = session(carol);
        for (String target : new String[] {"/?p=" + draft.value() + "&preview=true", "/?p=" + draft.value()}) {
            page(carols)
                    .get(target)
                    .then()
                    .statusCode(200)
                    .contentType(HTML)
                    .header("Cache-Control", "no-cache, must-revalidate, max-age=0")
                    .body(allOf(
                            startsWith("<!DOCTYPE html>"),
                            containsString("<title>Draft title &#8211; " + siteName + "</title>"),
                            containsString("<h1 class=\"entry-title\">Draft title</h1>"),
                            containsString("October 18, 2026")));
        }
        // the page is the same through index.php, and a HEAD has no body
        page(carols).get("/index.php?p=" + draft.value()).then().statusCode(200);
        page(carols).head("/?p=" + draft.value()).then().statusCode(200);
        // bob edits others' posts; erin edits nothing
        page(session(bob)).get("/?p=" + draft.value()).then().statusCode(200);
        page(session(erin)).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // pending and scheduled posts follow the same gate; private ones need read_post
        PostId pending = PostFixtures.builder(site)
                .author(carol)
                .status("pending")
                .publishedAt(null)
                .title("Pending title")
                .insert(data);
        page(carols).get("/?p=" + pending.value()).then().statusCode(200).body(containsString("October 18, 2026"));
        page(null).get("/?p=" + pending.value()).then().statusCode(404);
        PostId scheduled = PostFixtures.builder(site)
                .author(carol)
                .status("future")
                .publishedAt("2030-01-01T10:00:00Z")
                .title("Scheduled title")
                .insert(data);
        page(carols).get("/?p=" + scheduled.value()).then().statusCode(200).body(containsString("January 1, 2030"));
        page(null).get("/?p=" + scheduled.value()).then().statusCode(404);
        PostId secret = PostFixtures.builder(site)
                .author(bob)
                .status("private")
                .title("Private title")
                .insert(data);
        // a private post keeps its own date: only drafts and pending posts are shown as of now
        page(session(bob))
                .get("/?p=" + secret.value())
                .then()
                .statusCode(200)
                .body(allOf(containsString("January 10, 2026"), not(containsString("October 18, 2026"))));
        page(carols).get("/?p=" + secret.value()).then().statusCode(404);
        page(null).get("/?p=" + secret.value()).then().statusCode(404);
        // nobody sees an auto-draft or a trashed post, nor a revision
        for (String status : new String[] {"auto-draft", "trash"}) {
            PostId hidden = PostFixtures.builder(site)
                    .author(carol)
                    .status(status)
                    .publishedAt(null)
                    .title("Hidden " + status)
                    .insert(data);
            page(carols).get("/?p=" + hidden.value() + "&preview=true").then().statusCode(404);
            page(session(alice))
                    .get("/?p=" + hidden.value() + "&preview=true")
                    .then()
                    .statusCode(404);
        }
        // a missing post, and a page asked for as a post
        page(carols)
                .get("/?p=" + (draft.value() + 100_000) + "&preview=true")
                .then()
                .statusCode(404);
        PostId page = PostFixtures.builder(site)
                .type("page")
                .author(bob)
                .title("A page")
                .insert(data);
        // a published post of another public type: WordPress's canonical redirect of a 404 to the post's own link
        page(null)
                .get("/?p=" + page.value())
                .then()
                .statusCode(301)
                .header("Location", "http://localhost/?page_id=" + page.value())
                .header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private");
        page(null).get("/?attachment_id=" + secret.value()).then().statusCode(404);
        page(null).get("/?page_id=" + draft.value()).then().statusCode(404);
        page(null).get("/?page_id=" + page.value()).then().statusCode(200).body(containsString("A page"));
        page(null).get("/?preview=true").then().statusCode(404);
    }

    @Test
    void aBadPairIs403EvenForPublishedPosts() {
        clock.set(NOW);
        PostId published = PostFixtures.published(data, site, UserId.of(carol), "Public title", "2026-01-10T10:00:00Z");
        String pair = "/?p=" + published.value() + "&preview_id=" + published.value() + "&preview_nonce=bad";
        page(null).get(pair).then().statusCode(403).contentType(HTML).body(containsString(CANNOT_PREVIEW));
        page(null)
                .header("Accept", "application/json")
                .get(pair)
                .then()
                .statusCode(403)
                .contentType("application/json; charset=UTF-8")
                .body(equalTo("{\"code\":\"wp_die\",\"message\":\"" + CANNOT_PREVIEW
                        + "\",\"data\":{\"status\":403},\"additional_errors\":[]}"));
        // only one of the two: no check
        page(null)
                .get("/?p=" + published.value() + "&preview_id=" + published.value())
                .then()
                .statusCode(200);
        page(null).get("/?p=" + published.value() + "&preview_nonce=bad").then().statusCode(200);
        // the viewer's session does not help with a bad token, nor with empty values
        page(session(carol)).get(pair).then().statusCode(403).body(containsString(CANNOT_PREVIEW));
        page(session(carol))
                .get("/?p=" + published.value() + "&preview_id=&preview_nonce=")
                .then()
                .statusCode(403);
        // the check comes first: a missing post with a bad pair is 403, not 404
        page(null).get("/?p=0&preview_id=0&preview_nonce=bad").then().statusCode(403);
    }

    @Test
    void aPublishedPostWithPreviewTrueIsRedirectedToItsPlainLink() {
        clock.set(NOW);
        PostId published = PostFixtures.published(data, site, UserId.of(carol), "Public title", "2026-01-10T10:00:00Z");
        for (String cookie : new String[] {null, session(carol)}) {
            page(cookie)
                    .get("/?p=" + published.value() + "&preview=true")
                    .then()
                    .statusCode(301)
                    .header("Location", "http://localhost/?p=" + published.value())
                    .header("X-Redirect-By", nullValue());
        }
        page(null)
                .get("/?p=" + published.value())
                .then()
                .statusCode(200)
                .body(containsString("<h1 class=\"entry-title\">Public title</h1>"));
        // a page redirects to its own plain link; preview=0 is no preview
        PostId page = PostFixtures.builder(site)
                .type("page")
                .author(bob)
                .title("A page")
                .insert(data);
        page(null)
                .get("/?page_id=" + page.value() + "&preview=true")
                .then()
                .statusCode(301)
                .header("Location", "http://localhost/?page_id=" + page.value());
        page(null).get("/?p=" + published.value() + "&preview=0").then().statusCode(200);
        page(null)
                .get("/?page_id=" + published.value())
                .then()
                .statusCode(301)
                .header("Location", "http://localhost/?p=" + published.value());
        // a draft is never redirected
        PostId draft = draftOf(carol, "Draft title");
        page(session(carol))
                .get("/?p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(200);
    }

    @Test
    void aTypeThatIsNotPubliclyQueryableIsNeverShown() {
        clock.set(NOW);
        PostId pattern = PostFixtures.builder(site)
                .type("wp_block")
                .author(bob)
                .title("Pattern title")
                .content("PATTERN BODY")
                .insert(data);
        PostId ledger = PostFixtures.builder(site)
                .type("ledger")
                .author(bob)
                .title("Ledger title")
                .content("LEDGER BODY")
                .insert(data);
        // a post_type that is not publicly queryable is dropped, so the id is looked up as a post: not found
        for (String cookie : new String[] {null, session(bob)}) {
            page(cookie)
                    .get("/?p=" + pattern.value() + "&post_type=wp_block")
                    .then()
                    .statusCode(404)
                    .body(not(containsString("PATTERN BODY")));
            page(cookie)
                    .get("/?p=" + ledger.value() + "&post_type=ledger")
                    .then()
                    .statusCode(404)
                    .body(not(containsString("LEDGER BODY")));
        }
        // nor redirected: a block is of a type that is not public
        page(null).get("/?page_id=" + pattern.value()).then().statusCode(404);
        // a post asked for with such a post_type is the post itself, as is one with a post_type nobody registered
        PostId published = PostFixtures.published(data, site, UserId.of(carol), "Public title", "2026-01-10T10:00:00Z");
        for (String type : new String[] {"wp_block", "ledger", "page", "nothing-here"}) {
            page(null)
                    .get("/?p=" + published.value() + "&post_type=" + type)
                    .then()
                    .statusCode(200)
                    .body(containsString("<h1 class=\"entry-title\">Public title</h1>"));
        }
    }

    @Test
    void ofSeveralSessionCookiesTheOneThisSiteKnowsCounts() {
        clock.set(NOW);
        PostId draft = draftOf(carol, "Cookie draft");
        String good = session(carol);
        String target = "/?p=" + draft.value() + "&preview=true";
        // a browser sends every cookie whose path matches: a stale or another path's one may come first or last
        for (String header : new String[] {
            COOKIE + "=stale-token; " + COOKIE + "=" + good, COOKIE + "=" + good + "; " + COOKIE + "=stale-token"
        }) {
            Http.as(null)
                    .header("Cookie", header)
                    .get(target)
                    .then()
                    .statusCode(200)
                    .body(containsString("<h1 class=\"entry-title\">Cookie draft</h1>"));
        }
        Http.as(null)
                .header("Cookie", COOKIE + "=stale-token; " + COOKIE + "=other-stale")
                .get(target)
                .then()
                .statusCode(404);
    }

    @Test
    void aTokenForAnotherPostIs403() {
        clock.set(NOW);
        PostId mine = PostFixtures.published(data, site, UserId.of(carol), "Mine", "2026-01-10T10:00:00Z");
        PostId other = PostFixtures.published(data, site, UserId.of(carol), "Other", "2026-01-10T10:00:00Z");
        String carols = session(carol);
        String token = token(mine, carol, NOW);
        page(carols).get(preview(mine, token)).then().statusCode(200);
        page(carols).get(preview(other, token)).then().statusCode(403).body(containsString(CANNOT_PREVIEW));
        page(carols)
                .get("/?p=" + mine.value() + "&preview_id=" + other.value() + "&preview_nonce=" + token
                        + "&preview=true")
                .then()
                .statusCode(403);
        // another viewer with the same token
        page(session(bob)).get(preview(mine, token)).then().statusCode(403);
    }

    @Test
    void aDemotedUserNoLongerPreviews() {
        clock.set(NOW);
        PostId draft = draftOf(carol, "Draft title");
        String carols = session(carol);
        String token = token(draft, carol, NOW);
        page(carols).get(preview(draft, token)).then().statusCode(200);
        roles(carol, "subscriber");
        try {
            page(carols).get(preview(draft, token)).then().statusCode(403).body(containsString(CANNOT_PREVIEW));
            page(carols).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        } finally {
            roles(carol, "author");
        }
        page(carols).get(preview(draft, token)).then().statusCode(200);
        // an expired session makes the viewer anonymous: the draft is not found, a valid pair is refused
        clock.set(NOW.plus(Duration.ofHours(12)).plusSeconds(1));
        page(carols).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        page(carols)
                .get(preview(draft, token(draft, carol, clock.now())))
                .then()
                .statusCode(403)
                .body(containsString(CANNOT_PREVIEW));
    }

    @Test
    void aPreviewTokenOutlivesItsTickOnceAndTheSecretStaysHidden() {
        clock.set(NOW);
        PostId draft = draftOf(carol, "Draft title");
        String token = token(draft, carol, NOW);
        page(session(carol)).get(preview(draft, token)).then().statusCode(200);
        clock.set(NOW.plus(Duration.ofHours(6)));
        page(session(carol)).get(preview(draft, token)).then().statusCode(200);
        clock.set(NOW.plus(Duration.ofHours(12)));
        page(session(carol)).get(preview(draft, token)).then().statusCode(403);
        clock.set(NOW);
        // the secret is no setting
        Response settings = as(alice).get("/wp-json/wp/v2/settings");
        settings.then().statusCode(200);
        assertThat(settings.asString()).doesNotContain(SECRET_OPTION);
        assertThat(as(alice).get("/wp-json/wp/v2/settings?context=edit").asString())
                .doesNotContain(SECRET_OPTION);
        // deleting the option revokes every link
        Jdbc.deleteOption(site, SECRET_OPTION);
        page(session(carol)).get(preview(draft, token)).then().statusCode(403);
        // and no verification creates one
        assertThat(Jdbc.optionJson(site, SECRET_OPTION)).isNull();
    }

    @Test
    void theOverlayShowsTheLatestAutosaveOfAnyUser() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("stored title")
                .content("stored text")
                .insert(data);
        long carolsAutosave = autosave(post, carol, "f17 carol title", "carol text", "2026-10-18T09:50:00Z");
        long bobsAutosave = autosave(post, bob, "f17 bob title", "bob text", "2026-10-18T09:55:00Z");
        String carols = session(carol);
        String token = token(post, carol, NOW);
        Response overlaid = page(carols).get(preview(post, token));
        overlaid.then()
                .statusCode(200)
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0")
                .body(allOf(
                        containsString("<title>f17 bob title &#8211; " + siteName + "</title>"),
                        containsString("<h1 class=\"entry-title\">f17 bob title</h1>"),
                        containsString("bob text"),
                        not(containsString("carol text")),
                        not(containsString("stored text")),
                        containsString("single-format-standard"),
                        not(containsString("post-thumbnail"))));
        // without the pair, the published post is redirected; with the pair but no preview, shown as stored
        page(carols).get("/?p=" + post.value() + "&preview=true").then().statusCode(301);
        page(carols)
                .get("/?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=" + token)
                .then()
                .statusCode(200)
                .body(allOf(containsString("stored text"), not(containsString("bob text"))));
        // nobody else's pair: the token is the viewer's
        page(null).get(preview(post, token)).then().statusCode(403);
        page(session(erin)).get(preview(post, token)).then().statusCode(403);
        // the format and the featured image of the request, the latter for the previewed post only
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'post_format',"
                        + " 'post-format-aside', 'post-format-aside', '')",
                site.value());
        page(carols)
                .get(preview(post, token) + "&post_format=aside&_thumbnail_id=77")
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("single-format-aside"),
                        containsString("<figure class=\"post-thumbnail\" data-attachment-id=\"77\"></figure>")));
        page(carols)
                .get(preview(post, token) + "&post_format=chat&_thumbnail_id=-1")
                .then()
                .statusCode(200)
                .body(allOf(containsString("single-format-standard"), not(containsString("post-thumbnail"))));
        // an autosave with an empty title overlays an empty title: the document title is the site's name alone
        Jdbc.update(data, "update qp_revision set title = '' where id = ?", bobsAutosave);
        page(carols)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<title>" + siteName + "</title>"),
                        containsString("<h1 class=\"entry-title\"></h1>")));
        // the latest autosave is the newest by time, whoever wrote it
        Jdbc.update(data, "delete from qp_revision where id = ?", bobsAutosave);
        page(carols)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .body(allOf(containsString("f17 carol title"), containsString("carol text")));
        assertThat(carolsAutosave).isPositive();
    }

    @Test
    void theCookieNeverAuthenticatesRest() {
        clock.set(NOW);
        String carols = session(carol);
        PostId draft = draftOf(carol, "Draft title");
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
    void theCookieIsScopedToItsSitePath() {
        clock.set(NOW);
        String news = "news-" + UUID.randomUUID();
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        SiteId newsSite = SiteId.of(Jdbc.count(data, "select id from qp_site where path = ?", "/" + news + "/"));
        TestData.member(data, newsSite, UserId.of(carol), "[\"author\"]");
        Response opened = as(carol).post("/" + news + "/wp-json/quarkpress/v1/preview/session");
        opened.then().statusCode(200).header("Set-Cookie", containsString("Path=/" + news + "/;"));
        String theirs = opened.getCookie(COOKIE);
        PostId draft = draftOf(carol, "Main draft");
        page(theirs).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // and the main site's session opens nothing on the subdirectory site
        PostId newsDraft = PostFixtures.builder(newsSite)
                .author(carol)
                .status("draft")
                .publishedAt(null)
                .title("News draft")
                .insert(data);
        page(session(carol)).get("/" + news + "/?p=" + newsDraft.value()).then().statusCode(404);
        page(theirs).get("/" + news + "/?p=" + newsDraft.value()).then().statusCode(200);
    }

    @Test
    void restIgnoresPreviewParameters() {
        clock.set(NOW);
        as(null).get("/wp-json/wp/v2/posts?preview_id=1&preview_nonce=bad")
                .then()
                .statusCode(200);
        as(carol)
                .get("/wp-json/wp/v2/posts?preview_id=1&preview_nonce=bad&preview=true")
                .then()
                .statusCode(200);
    }

    @Test
    void aTokenNeverShowsAnotherSitesPost() {
        clock.set(NOW);
        SiteId other = TestData.site(data);
        TestData.member(data, other, UserId.of(bob), "[\"administrator\"]");
        PostId theirs = PostFixtures.builder(other)
                .status("draft")
                .author(bob)
                .title("Theirs only")
                .publishedAt(null)
                .insert(data);
        String bobs = session(bob);
        page(bobs).get("/?p=" + theirs.value() + "&preview=true").then().statusCode(404);
        Response refused = page(bobs).get(preview(theirs, token(theirs, bob, NOW)));
        refused.then().statusCode(403);
        assertThat(refused.asString()).doesNotContain("Theirs only");
        PostId published = PostFixtures.published(data, other, UserId.of(bob), "Theirs public", "2026-01-10T10:00:00Z");
        Response missing = page(null).get("/?p=" + published.value());
        missing.then().statusCode(404);
        assertThat(missing.asString()).doesNotContain("Theirs public");
    }

    // ---- helpers

    /** A REST request to the main site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.as(user == null ? null : "test-" + user);
    }

    /** A front-end request to the main site with the session cookie, or without one when null. */
    private static RequestSpecification page(String cookie) {
        RequestSpecification spec = Http.as(null);
        return cookie == null ? spec : spec.cookie(COOKIE, cookie);
    }

    /** A fresh session token of the user on the main site. */
    private static String session(long user) {
        Response opened = as(user).post(SESSION);
        opened.then().statusCode(200);
        return opened.getCookie(COOKIE);
    }

    /** The editor's preview link of {@code post} for a token: the pair and {@code preview=true}. */
    private static String preview(PostId post, String token) {
        return "/?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=" + token + "&preview=true";
    }

    /**
     * The preview token of {@code post} for {@code user} at {@code now}, computed as the posts module computes it
     * (base64url without padding of the first 20 bytes of HMAC-SHA256 over {@code preview|site|post|user|tick} with
     * the site's secret, the tick being the time in 6 hour steps); the secret is created when the site has none.
     */
    private static String token(PostId post, long user, Instant now) {
        String stored = Jdbc.optionJson(site, SECRET_OPTION);
        if (stored == null) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            stored = "\"" + Base64.getEncoder().encodeToString(secret) + "\"";
            Jdbc.option(site, SECRET_OPTION, stored);
        }
        byte[] secret = Base64.getDecoder().decode(stored.substring(1, stored.length() - 1));
        long tick = Math.floorDiv(now.getEpochSecond(), LIFETIME / 2);
        String message = "preview|" + site.value() + "|" + post.value() + "|" + user + "|" + tick;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[20];
            System.arraycopy(full, 0, truncated, 0, 20);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An autosave row of {@code author} on the post, written past the services; answers its id. */
    private static long autosave(PostId post, long author, String title, String content, String createdAt) {
        String marker = "rev-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qp_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", true, ?, "
                        + PostFixtures.instant() + ")",
                site.value(),
                post.value(),
                author,
                title,
                content,
                marker,
                "{}",
                author,
                PostFixtures.instantText(createdAt));
        return Jdbc.count(data, "select id from qp_revision where excerpt = ?", marker);
    }

    /** Sets the user's roles on the main site to {@code role} alone, past the services. */
    private static void roles(long user, String role) {
        Jdbc.update(
                data,
                "update qp_site_membership set roles = " + TestDb.json() + " where site_id = ? and user_id = ?",
                "[\"" + role + "\"]",
                site.value(),
                user);
    }

    private static PostId draftOf(long author, String title) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title(title)
                .publishedAt(null)
                .insert(data);
    }
}
