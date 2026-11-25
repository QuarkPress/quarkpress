package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.front.runtime.PreviewViewer;
import org.quarkword.posts.PreviewSessions;
import org.quarkword.posts.PreviewTokens;

/**
 * Previews over HTTP: the viewer is the user of the {@code quarkword_preview} session cookie (sessions opened through
 * the posts module, as the REST module's session route opens them), who sees a draft, the preview pair's 403 as HTML
 * and as JSON, the canonical redirect of a published post asked for as a preview, the overlay of the latest autosave
 * of any user, a password that previewing does not lift, and the credentials that never open a preview (another
 * post's token, a demoted user, an expired session or token, another site's session or post). A site of its own with
 * plain permalinks, {@code home} carrying the test port; members alice (administrator), bob (editor), carol (author)
 * and erin (subscriber); the clock is the test's.
 */
class PreviewDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(PreviewDbTest.class, TestClock.class, LedgerType.class);

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private static final String CANNOT_PREVIEW = "Sorry, you are not allowed to preview drafts.";

    private static final String HTML = "text/html; charset=UTF-8";

    private static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";

    private static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";

    private static final String SITE_NAME = "Preview test";

    private static final String PUBLIC_ROBOTS =
            "<meta name='robots' content='max-image-preview:large, noindex, follow' />";

    private static final String PRIVATE_ROBOTS = "<meta name='robots' content='noindex, nofollow' />";

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    TestClock clock;

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static String home;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId erin;

    @BeforeEach
    void cast() {
        clock.set(NOW);
        if (site != null) {
            return;
        }
        data = dataSource;
        site = TestData.site(data);
        host = TestData.domain(data, site) + ":" + RestAssured.port;
        home = "http://" + host;
        TestData.textOption(data, site, "home", home);
        TestData.textOption(data, site, "siteurl", home);
        TestData.textOption(data, site, "permalink_structure", "");
        TestData.textOption(data, site, "blogname", SITE_NAME);
        alice = member(site, "alice", "administrator");
        bob = member(site, "bob", "editor");
        carol = member(site, "carol", "author");
        erin = member(site, "erin", "subscriber");
    }

    private static UserId member(SiteId on, String name, String role) {
        UserId user = TestData.profile(data, name, name + "-" + UUID.randomUUID());
        TestData.member(data, on, user, "[\"" + role + "\"]");
        return user;
    }

    @Test
    void theCookieNameIsTheSessionRoutes() {
        assertThat(PreviewViewer.COOKIE).isEqualTo("quarkword_preview");
    }

    @Test
    void aSessionOpensDraftsUntilItIsClosed() throws Exception {
        PostId draft = draft(carol, "Session draft");
        String first = session(carol);
        String second = session(carol);
        page(first).get("/?p=" + draft.value() + "&preview=true").then().statusCode(200);
        page(second).get("/?p=" + draft.value() + "&preview=true").then().statusCode(200);
        Identities.onSite(site, Identities.user(carol), () -> {
            Arc.container().instance(PreviewSessions.class).get().closeAll(carol);
            return null;
        });
        page(first).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        page(second).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // a subscriber's session opens nothing more than anyone sees
        page(session(erin)).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
    }

    @Test
    void aProtectedPostShowsNoContent() throws Exception {
        PostId locked = post(bob, "publish", "Locked title", "SECRET", "2026-01-10T10:00:00Z");
        password(locked, "s3");
        for (String cookie : new String[] {null, session(erin), session(carol), session(bob)}) {
            page(cookie)
                    .get("/?p=" + locked.value())
                    .then()
                    .statusCode(200)
                    .contentType(HTML)
                    .header("Cache-Control", NO_CACHE)
                    .body(allOf(
                            containsString("<h1 class=\"entry-title\">Locked title</h1>"),
                            containsString("<div class=\"entry-content\"></div>"),
                            not(containsString("SECRET"))));
        }
    }

    @Test
    void previewDoesNotLiftThePassword() throws Exception {
        // the author previewing her own protected draft sees the form like everyone else (the form arrives later)
        PostId own = draft(carol, "Own locked draft");
        content(own, "SECRET");
        password(own, "s3");
        page(session(carol))
                .get("/?p=" + own.value() + "&preview=true")
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<h1 class=\"entry-title\">Own locked draft</h1>"),
                        containsString("<div class=\"entry-content\"></div>"),
                        not(containsString("SECRET"))));
        // and so does an editor with a valid pair on a published post
        PostId published = post(bob, "publish", "Locked published", "SECRET", "2026-01-10T10:00:00Z");
        password(published, "s3");
        page(session(bob))
                .get(preview(published, token(published, bob)))
                .then()
                .statusCode(200)
                .body(not(containsString("SECRET")));
        page(session(erin)).get("/?p=" + own.value() + "&preview=true").then().statusCode(404);
    }

    @Test
    void draftsNeedAViewerWhoMayEditThem() throws Exception {
        PostId draft = draft(carol, "Draft title");
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
                    .header("Cache-Control", NO_CACHE)
                    .header("Expires", EXPIRES)
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .body(allOf(
                            startsWith("<!DOCTYPE html>"),
                            containsString("<title>Draft title &#8211; " + SITE_NAME + "</title>"),
                            containsString("<h1 class=\"entry-title\">Draft title</h1>"),
                            containsString("October 18, 2026")));
        }
        // the page is the same through index.php, and a HEAD has no body
        page(carols).get("/index.php?p=" + draft.value()).then().statusCode(200);
        page(carols).head("/?p=" + draft.value()).then().statusCode(200).body(emptyString());
        // bob edits others' posts; erin edits nothing
        page(session(bob)).get("/?p=" + draft.value()).then().statusCode(200);
        page(session(erin)).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // pending and scheduled posts follow the same gate; private ones need read_post
        PostId pending = floating(carol, "pending", "Pending title");
        page(carols).get("/?p=" + pending.value()).then().statusCode(200).body(containsString("October 18, 2026"));
        page(null).get("/?p=" + pending.value()).then().statusCode(404);
        PostId scheduled = post(carol, "future", "Scheduled title", "", "2030-01-01T10:00:00Z");
        page(carols).get("/?p=" + scheduled.value()).then().statusCode(200).body(containsString("January 1, 2030"));
        page(null).get("/?p=" + scheduled.value()).then().statusCode(404);
        PostId secret = post(bob, "private", "Private title", "", "2026-01-10T10:00:00Z");
        // a private post keeps its own date: only drafts and pending posts are shown as of now
        page(session(bob))
                .get("/?p=" + secret.value())
                .then()
                .statusCode(200)
                .body(allOf(containsString("January 10, 2026"), not(containsString("October 18, 2026"))));
        page(carols).get("/?p=" + secret.value()).then().statusCode(404);
        page(null).get("/?p=" + secret.value()).then().statusCode(404);
        // nobody sees an auto-draft or a trashed post; a not-found request is no preview (WordPress's set_404 clears
        // the flag), so the canonical redirect drops "preview" unless the post it names cancels the redirect: a
        // trashed post does, an auto-draft (never a redirect target) and a missing post do not
        PostId trashed = floating(carol, "trash", "Hidden trash");
        PostId autoDraft = floating(carol, "auto-draft", "Hidden auto-draft");
        for (String cookie : new String[] {carols, session(alice)}) {
            page(cookie).get("/?p=" + trashed.value() + "&preview=true").then().statusCode(404);
            page(cookie).get("/?p=" + trashed.value()).then().statusCode(404);
            page(cookie)
                    .get("/?p=" + autoDraft.value() + "&preview=true")
                    .then()
                    .statusCode(301)
                    .header("Location", home + "/?p=" + autoDraft.value());
            page(cookie).get("/?p=" + autoDraft.value()).then().statusCode(404);
        }
        // a missing post
        long missing = draft.value() + 100_000;
        page(carols)
                .get("/?p=" + missing + "&preview=true")
                .then()
                .statusCode(301)
                .header("Location", home + "/?p=" + missing);
        page(carols).get("/?p=" + missing).then().statusCode(404);
        PostId page = post(bob, "publish", "A page", "", "2026-01-10T10:00:00Z", "page");
        // a published post of another public type: WordPress's canonical redirect of a 404 to the post's own link
        page(null)
                .get("/?p=" + page.value())
                .then()
                .statusCode(301)
                .header("Location", home + "/?page_id=" + page.value())
                .header("X-Redirect-By", "QuarkWord")
                .header("Expires", EXPIRES)
                .header("Cache-Control", NO_CACHE);
        page(null).get("/?attachment_id=" + secret.value()).then().statusCode(404);
        page(null).get("/?page_id=" + draft.value()).then().statusCode(404);
        page(null).get("/?page_id=" + page.value()).then().statusCode(200).body(containsString("A page"));
        // a preview naming nothing is the home list, as WordPress's
        page(null).get("/?preview=true").then().statusCode(200).body(containsString("<body class=\"home blog\">"));
    }

    @Test
    void aBadPairIs403EvenForPublishedPosts() throws Exception {
        PostId published = post(carol, "publish", "Public title", "", "2026-01-10T10:00:00Z");
        String pair = "/?p=" + published.value() + "&preview_id=" + published.value() + "&preview_nonce=bad";
        page(null)
                .get(pair)
                .then()
                .statusCode(403)
                .contentType(HTML)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("<div class=\"wp-die-message\">" + CANNOT_PREVIEW + "</div>"));
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
    void theErrorPagesRobotsMetaFollowsBlogPublicNotTheStatus() throws Exception {
        PostId published = post(carol, "publish", "Robots title", "", "2026-01-10T10:00:00Z");
        String pair = "/?p=" + published.value() + "&preview_id=" + published.value() + "&preview_nonce=bad";
        page(null).get(pair).then().statusCode(403).body(containsString(PUBLIC_ROBOTS));
        Jdbc.update(data, "update qw_site set public_flag = ? where id = ?", 0, site.value());
        try {
            page(null).get(pair).then().statusCode(403).body(containsString(PRIVATE_ROBOTS));
        } finally {
            Jdbc.update(data, "update qw_site set public_flag = ? where id = ?", 1, site.value());
        }
    }

    @Test
    void aBadPairIsCheckedBeforeTheSiteGate() throws Exception {
        // WordPress checks the pair on init, before its site check: a gone site answers a bad pair 403
        PostId published = post(carol, "publish", "Gone title", "", "2026-01-10T10:00:00Z");
        String bad = "/?p=" + published.value() + "&preview_id=" + published.value() + "&preview_nonce=bad";
        String good = preview(published, token(published, carol));
        String carols = session(carol);
        for (String flag : new String[] {"archived", "deleted"}) {
            String message = flag.equals("deleted")
                    ? "This site is no longer available."
                    : "This site has been archived or suspended.";
            Jdbc.update(data, "update qw_site set " + flag + " = ? where id = ?", true, site.value());
            try {
                page(null)
                        .get(bad)
                        .then()
                        .statusCode(403)
                        .contentType(HTML)
                        .header("Cache-Control", NO_CACHE)
                        .body(allOf(
                                containsString("<div class=\"wp-die-message\">" + CANNOT_PREVIEW + "</div>"),
                                containsString(PUBLIC_ROBOTS)));
                page(carols).get(bad).then().statusCode(403).body(containsString(CANNOT_PREVIEW));
                // a valid pair, or none, reaches the site gate
                page(carols)
                        .get(good)
                        .then()
                        .statusCode(410)
                        .body(allOf(containsString(message), containsString(PUBLIC_ROBOTS)));
                page(null).get("/?p=" + published.value()).then().statusCode(410);
            } finally {
                Jdbc.update(data, "update qw_site set " + flag + " = ? where id = ?", false, site.value());
            }
        }
    }

    @Test
    void aPublishedPostWithPreviewTrueIsRedirectedToItsPlainLink() throws Exception {
        PostId published = post(carol, "publish", "Public title", "", "2026-01-10T10:00:00Z");
        for (String cookie : new String[] {null, session(carol)}) {
            page(cookie)
                    .get("/?p=" + published.value() + "&preview=true")
                    .then()
                    .statusCode(301)
                    .header("Location", home + "/?p=" + published.value())
                    .header("X-Redirect-By", "QuarkWord")
                    // the redirect is no preview any more, as in WordPress: no preview headers
                    .header("X-Robots-Tag", nullValue())
                    .header("Content-Security-Policy", nullValue());
        }
        // and the anonymous visitor's redirect is cacheable as WordPress's is
        page(null)
                .get("/?p=" + published.value() + "&preview=true")
                .then()
                .statusCode(301)
                .header("Cache-Control", nullValue())
                .header("Expires", nullValue());
        page(null)
                .get("/?p=" + published.value())
                .then()
                .statusCode(200)
                .body(containsString("<h1 class=\"entry-title\">Public title</h1>"));
        // a page redirects to its own plain link; preview=0 is no preview
        PostId page = post(bob, "publish", "A page", "", "2026-01-10T10:00:00Z", "page");
        page(null)
                .get("/?page_id=" + page.value() + "&preview=true")
                .then()
                .statusCode(301)
                .header("Location", home + "/?page_id=" + page.value());
        page(null).get("/?p=" + published.value() + "&preview=0").then().statusCode(200);
        page(null)
                .get("/?page_id=" + published.value())
                .then()
                .statusCode(301)
                .header("Location", home + "/?p=" + published.value());
        // a draft is never redirected
        PostId draft = draft(carol, "Draft title");
        page(session(carol))
                .get("/?p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(200);
    }

    @Test
    void aTypeThatIsNotPubliclyQueryableIsNeverShown() throws Exception {
        PostId pattern = post(bob, "publish", "Pattern title", "PATTERN BODY", "2026-01-10T10:00:00Z", "wp_block");
        PostId ledger = post(bob, "publish", "Ledger title", "LEDGER BODY", "2026-01-10T10:00:00Z", "ledger");
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
        PostId published = post(carol, "publish", "Public title", "", "2026-01-10T10:00:00Z");
        for (String type : new String[] {"wp_block", "ledger", "page", "nothing-here"}) {
            page(null)
                    .get("/?p=" + published.value() + "&post_type=" + type)
                    .then()
                    .statusCode(200)
                    .body(containsString("<h1 class=\"entry-title\">Public title</h1>"));
        }
    }

    @Test
    void ofSeveralSessionCookiesTheOneThisSiteKnowsCounts() throws Exception {
        PostId draft = draft(carol, "Cookie draft");
        String good = session(carol);
        String target = "/?p=" + draft.value() + "&preview=true";
        // a browser sends every cookie whose path matches: a stale or another path's one may come first or last
        for (String header : new String[] {
            PreviewViewer.COOKIE + "=stale-token; " + PreviewViewer.COOKIE + "=" + good,
            PreviewViewer.COOKIE + "=" + good + "; " + PreviewViewer.COOKIE + "=stale-token"
        }) {
            page(null)
                    .header("Cookie", header)
                    .get(target)
                    .then()
                    .statusCode(200)
                    .body(containsString("<h1 class=\"entry-title\">Cookie draft</h1>"));
        }
        page(null)
                .header("Cookie", PreviewViewer.COOKIE + "=stale-token; " + PreviewViewer.COOKIE + "=other-stale")
                .get(target)
                .then()
                .statusCode(404);
    }

    @Test
    void aTokenForAnotherPostIs403() throws Exception {
        PostId mine = post(carol, "publish", "Mine", "", "2026-01-10T10:00:00Z");
        PostId other = post(carol, "publish", "Other", "", "2026-01-10T10:00:00Z");
        String carols = session(carol);
        String token = token(mine, carol);
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
    void aDemotedUserNoLongerPreviews() throws Exception {
        PostId draft = draft(carol, "Draft title");
        String carols = session(carol);
        String token = token(draft, carol);
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
                .get(preview(draft, token(draft, carol)))
                .then()
                .statusCode(403)
                .body(containsString(CANNOT_PREVIEW));
    }

    @Test
    void aPreviewTokenOutlivesItsTickOnce() throws Exception {
        PostId draft = draft(carol, "Draft title");
        String token = token(draft, carol);
        page(session(carol)).get(preview(draft, token)).then().statusCode(200);
        clock.set(NOW.plus(Duration.ofHours(6)));
        page(session(carol)).get(preview(draft, token)).then().statusCode(200);
        clock.set(NOW.plus(Duration.ofHours(12)));
        page(session(carol)).get(preview(draft, token)).then().statusCode(403);
        clock.set(NOW);
        // deleting the secret revokes every link, and no verification creates one
        TestData.dropOption(data, site, PreviewTokens.SECRET_OPTION);
        page(session(carol)).get(preview(draft, token)).then().statusCode(403);
        assertThat(Jdbc.count(
                        data,
                        "select count(*) from qw_option where site_id = ? and name = ?",
                        site.value(),
                        PreviewTokens.SECRET_OPTION))
                .isZero();
    }

    @Test
    void theOverlayShowsTheLatestAutosaveOfAnyUser() throws Exception {
        PostId post = post(carol, "publish", "stored title", "stored text", "2026-01-10T10:00:00Z");
        long carolsAutosave = autosave(post, carol, "f17 carol title", "carol text", "2026-10-18T09:50:00Z");
        long bobsAutosave = autosave(post, bob, "f17 bob title", "bob text", "2026-10-18T09:55:00Z");
        String carols = session(carol);
        String token = token(post, carol);
        page(carols)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(allOf(
                        containsString("<title>f17 bob title &#8211; " + SITE_NAME + "</title>"),
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
                "insert into qw_term (site_id, taxonomy, name, slug, description) values (?, 'post_format',"
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
        Jdbc.update(data, "update qw_revision set title = '' where id = ?", bobsAutosave);
        page(carols)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<title>" + SITE_NAME + "</title>"),
                        containsString("<h1 class=\"entry-title\"></h1>")));
        // the latest autosave is the newest by time, whoever wrote it
        Jdbc.update(data, "delete from qw_revision where id = ?", bobsAutosave);
        page(carols)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .body(allOf(containsString("f17 carol title"), containsString("carol text")));
        assertThat(carolsAutosave).isPositive();
    }

    @Test
    void aPreviewWithoutAutosaveShowsTheStoredPost() throws Exception {
        String session = session(carol);
        PostId draft = draft(carol, "Draft without autosave");
        content(draft, "draft text");
        page(session)
                .get("/?p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<h1 class=\"entry-title\">Draft without autosave</h1>"),
                        containsString("draft text")));
        page(null).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // the editor's preview pair on a published post: the stored post, since no autosave exists
        PostId post = post(carol, "publish", "Published without autosave", "stored text", "2026-01-10T10:00:00Z");
        String token = token(post, carol);
        page(session)
                .get(preview(post, token))
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<h1 class=\"entry-title\">Published without autosave</h1>"),
                        containsString("stored text")));
        Response refused = page(null).get(preview(post, token));
        refused.then().statusCode(403);
        assertThat(refused.asString()).doesNotContain("stored text");
    }

    @Test
    void aSessionBelongsToTheSiteItWasOpenedOn() throws Exception {
        SiteId news = TestData.site(data);
        String newsHost = TestData.domain(data, news) + ":" + RestAssured.port;
        TestData.textOption(data, news, "home", "http://" + newsHost);
        TestData.textOption(data, news, "siteurl", "http://" + newsHost);
        TestData.textOption(data, news, "permalink_structure", "");
        TestData.member(data, news, carol, "[\"author\"]");
        String theirs = Identities.onSite(
                news,
                Identities.user(carol),
                () -> Arc.container()
                        .instance(PreviewSessions.class)
                        .get()
                        .open(carol)
                        .token());
        PostId draft = draft(carol, "Main draft");
        page(theirs).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // and this site's session opens nothing on the other site
        PostId newsDraft =
                TestData.post(data, news, "post", "draft", "news-draft", carol, null, "2026-10-18T09:00:00Z", true);
        Http.at(newsHost, null)
                .cookie(PreviewViewer.COOKIE, session(carol))
                .get("/?p=" + newsDraft.value())
                .then()
                .statusCode(404);
        Http.at(newsHost, null)
                .cookie(PreviewViewer.COOKIE, theirs)
                .get("/?p=" + newsDraft.value())
                .then()
                .statusCode(200);
    }

    @Test
    void aTokenNeverShowsAnotherSitesPost() throws Exception {
        SiteId other = TestData.site(data);
        TestData.member(data, other, bob, "[\"administrator\"]");
        PostId theirs = TestData.post(data, other, "post", "draft", "theirs", bob, null, "2026-10-18T09:00:00Z", true);
        Jdbc.update(data, "update qw_post set title = 'Theirs only' where id = ?", theirs.value());
        String bobs = session(bob);
        // not found here: the preview flag is dropped by the 404, and so is "preview" by the canonical redirect
        page(bobs)
                .get("/?p=" + theirs.value() + "&preview=true")
                .then()
                .statusCode(301)
                .header("Location", home + "/?p=" + theirs.value());
        Response notHere = page(bobs).get("/?p=" + theirs.value());
        notHere.then().statusCode(404);
        assertThat(notHere.asString()).doesNotContain("Theirs only");
        String theirToken = Identities.onSite(
                other,
                Identities.user(bob),
                () -> Arc.container().instance(PreviewTokens.class).get().issue(theirs, bob));
        Response refused = page(bobs).get(preview(theirs, theirToken));
        refused.then().statusCode(403);
        assertThat(refused.asString()).doesNotContain("Theirs only");
        PostId published = TestData.post(
                data, other, "post", "publish", "theirs-public", bob, null, "2026-01-10T10:00:00Z", false);
        Jdbc.update(data, "update qw_post set title = 'Theirs public' where id = ?", published.value());
        Response missing = page(null).get("/?p=" + published.value());
        missing.then().statusCode(404);
        assertThat(missing.asString()).doesNotContain("Theirs public");
    }

    // ---- helpers

    /** A front-end request to the site with the session cookie, or without one when null. */
    private static RequestSpecification page(String cookie) {
        RequestSpecification spec = Http.at(host, null);
        return cookie == null ? spec : spec.cookie(PreviewViewer.COOKIE, cookie);
    }

    /** A fresh session token of the user on the site. */
    private static String session(UserId user) throws Exception {
        return Identities.onSite(
                site,
                Identities.user(user),
                () -> Arc.container()
                        .instance(PreviewSessions.class)
                        .get()
                        .open(user)
                        .token());
    }

    /** The user's preview token of {@code post} now. */
    private static String token(PostId post, UserId user) throws Exception {
        return Identities.onSite(
                site,
                Identities.user(user),
                () -> Arc.container().instance(PreviewTokens.class).get().issue(post, user));
    }

    /** The editor's preview link of {@code post} for a token: the pair and {@code preview=true}. */
    private static String preview(PostId post, String token) {
        return "/?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=" + token + "&preview=true";
    }

    private static PostId post(UserId author, String status, String title, String content, String date) {
        return post(author, status, title, content, date, "post");
    }

    private static PostId post(UserId author, String status, String title, String content, String date, String type) {
        PostId post = TestData.post(data, site, type, status, "p-" + UUID.randomUUID(), author, null, date, false);
        Jdbc.update(data, "update qw_post set title = ?, content = ? where id = ?", title, content, post.value());
        return post;
    }

    /** A post whose date floats (a draft, a pending post), last modified an hour before the test's now. */
    private static PostId floating(UserId author, String status, String title) {
        PostId post = TestData.post(
                data, site, "post", status, "p-" + UUID.randomUUID(), author, null, "2026-10-18T09:00:00Z", true);
        Jdbc.update(data, "update qw_post set title = ? where id = ?", title, post.value());
        return post;
    }

    private static PostId draft(UserId author, String title) {
        return floating(author, "draft", title);
    }

    private static void content(PostId post, String content) {
        Jdbc.update(data, "update qw_post set content = ? where id = ?", content, post.value());
    }

    private static void password(PostId post, String password) {
        Jdbc.update(data, "update qw_post set password = ? where id = ?", password, post.value());
    }

    /** An autosave row of {@code author} on the post, written past the services; answers its id. */
    private static long autosave(PostId post, UserId author, String title, String content, String createdAt) {
        String marker = "rev-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qw_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", true, ?, "
                        + TestDb.instant() + ")",
                site.value(),
                post.value(),
                author.value(),
                title,
                content,
                marker,
                "{}",
                author.value(),
                TestDb.instantText(createdAt));
        return Jdbc.count(data, "select id from qw_revision where excerpt = ?", marker);
    }

    /** Sets the user's roles on the site to {@code role} alone, past the services. */
    private static void roles(UserId user, String role) {
        Jdbc.update(
                data,
                "update qw_site_membership set roles = " + TestDb.json() + " where site_id = ? and user_id = ?",
                "[\"" + role + "\"]",
                site.value(),
                user.value());
    }
}
