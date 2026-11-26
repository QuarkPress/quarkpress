package org.quarkword.it.front;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The front end of a single site over HTTP, beside the REST API and a resource of the host application: setup through
 * {@code /it/*} as alice (the site's administrator) with Keycloak tokens, the pages as an anonymous visitor (or with
 * the cookies a visitor is given). Every test starts from {@code home} and {@code siteurl} {@code http://localhost}
 * (the requests name {@code Host: localhost}, so no port is involved) and WordPress's default structure
 * {@code /%year%/%monthnum%/%day%/%postname%/}, and makes its own uniquely named content. The expected answers are
 * WordPress 7.1.3's for the same requests (front-facts.json G1, G13, G15, G20; the front module's tests), except where
 * the front module's README lists a difference (no {@code X-Pingback}, {@code X-Redirect-By: QuarkWord}, the preview
 * headers, {@code robots.txt} not served, the request bounds).
 */
@QuarkusTest
class FrontDbTest {

    static final String HOME = "http://localhost";
    static final String DATED = "/%year%/%monthnum%/%day%/%postname%/";
    static final String HTML = "text/html; charset=UTF-8";
    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String API = "<" + HOME + "/wp-json/>; rel=\"https://api.w.org/\"";

    FrontCalls calls = new FrontCalls();

    @BeforeEach
    void site() {
        calls.textOption("home", HOME);
        calls.textOption("siteurl", HOME);
        calls.textOption("permalink_structure", DATED);
    }

    private static String alternate(String route) {
        return "<" + HOME + "/wp-json/wp/v2/" + route
                + ">; rel=\"alternate\"; title=\"JSON\"; type=\"application/json\"";
    }

    private static String shortlink(long id) {
        return "<" + HOME + "/?p=" + id + ">; rel=shortlink";
    }

    /** A published post dated 2026-03-04 10:00 UTC (the site's zone is UTC) with the slug and content given. */
    private long published(String slug, String title, Map<String, Object> more) {
        Map<String, Object> fields = new LinkedHashMap<>(more);
        fields.put("slug", slug);
        fields.put("date_gmt", "2026-03-04T10:00:00");
        return calls.post("post", "publish", title, fields);
    }

    @Test
    void aPrettySingleWithWordPresssHeadersAndItsPlainLinkRedirected() {
        String slug = "pretty-" + FrontCalls.token();
        String title = "Pretty " + FrontCalls.token();
        long id = published(slug, title, Map.of("content", "The pretty words."));
        String permalink = HOME + "/2026/03/04/" + slug + "/";

        Response page = calls.visitor().get("/2026/03/04/" + slug + "/");
        page.then()
                .statusCode(200)
                .header("Content-Type", HTML)
                .header("Cache-Control", nullValue())
                .header("Expires", nullValue())
                .header("X-Pingback", nullValue())
                .body(containsString(title))
                .body(containsString("The pretty words."));
        assertThat(page.headers().getValues("Link")).containsExactly(API, alternate("posts/" + id), shortlink(id));

        calls.visitor()
                .get("/?p=" + id)
                .then()
                .statusCode(301)
                .header("Location", permalink)
                .header("X-Redirect-By", "QuarkWord")
                .header("Content-Type", HTML)
                .header("Cache-Control", nullValue());
    }

    @Test
    void anUnknownPathIsNotFoundAndNotCached() {
        Response missing = calls.visitor().get("/nope/");
        missing.then()
                .statusCode(404)
                .header("Cache-Control", NO_CACHE)
                .header("Expires", EXPIRES)
                .header("Content-Type", HTML)
                .body(containsString("Page not found"));
        assertThat(missing.headers().getValues("Link")).containsExactly(API);
    }

    @Test
    void aCategoryArchiveListsItsPosts() {
        Map<String, Object> news = calls.category("News " + FrontCalls.token());
        long category = ((Number) news.get("id")).longValue();
        String title = "Filed " + FrontCalls.token();
        published("filed-" + FrontCalls.token(), title, Map.of("categories", List.of(category)));

        Response archive = calls.visitor().get("/category/" + news.get("slug") + "/");
        archive.then()
                .statusCode(200)
                .header("Content-Type", HTML)
                .header("Cache-Control", nullValue())
                .body(containsString(title));
        assertThat(archive.headers().getValues("Link")).containsExactly(API, alternate("categories/" + category));
    }

    @Test
    void theHomeListIsPaged() {
        // eleven posts in a category of their own, so the second page of its archive holds exactly the oldest
        Map<String, Object> paged = calls.category("Paged " + FrontCalls.token());
        long category = ((Number) paged.get("id")).longValue();
        String archive = "/category/" + paged.get("slug") + "/";
        String oldest = "Oldest " + FrontCalls.token();
        for (int day = 1; day <= 11; day++) {
            calls.post(
                    "post",
                    "publish",
                    day == 1 ? oldest : "Old " + FrontCalls.token(),
                    Map.of("date_gmt", String.format("1990-01-%02dT10:00:00", day), "categories", List.of(category)));
        }
        calls.visitor().get("/").then().statusCode(200).body(not(containsString(oldest)));
        calls.visitor().get(archive).then().statusCode(200).body(not(containsString(oldest)));
        calls.visitor()
                .get(archive + "page/2/")
                .then()
                .statusCode(200)
                .header("Content-Type", HTML)
                .body(containsString(oldest));
        calls.visitor().get("/page/2/").then().statusCode(200).header("Content-Type", HTML);
        calls.visitor().get("/page/100000/").then().statusCode(404).header("Cache-Control", NO_CACHE);
    }

    @Test
    void theHostApplicationsRoutesWin() {
        Response ping = calls.visitor().get("/host/ping");
        ping.then().statusCode(200).body(equalTo("pong")).header("Expires", nullValue());
        assertThat(ping.headers().getValues("Link")).isEmpty();
    }

    @Test
    void theRestApiWinsOverTheFrontEnd() {
        long id = published("rest-" + FrontCalls.token(), "Rest " + FrontCalls.token(), Map.of());
        Response posts = calls.visitor().get("/wp-json/wp/v2/posts");
        posts.then().statusCode(200).header("Content-Type", startsWith("application/json"));
        assertThat(posts.jsonPath().getList("id", Long.class)).contains(id);
    }

    @Test
    void robotsTxtIsReservedAndNotFound() {
        calls.visitor()
                .get("/robots.txt")
                .then()
                .statusCode(404)
                .header("Cache-Control", NO_CACHE)
                .header("Content-Type", HTML);
    }

    @Test
    void anOldSlugRedirectsToThePostsNewLink() {
        String old = "old-" + FrontCalls.token();
        String renamed = "new-" + FrontCalls.token();
        long id = published(old, "Renamed " + FrontCalls.token(), Map.of());
        calls.slug(id, renamed);

        calls.visitor()
                .get("/2026/03/04/" + old + "/")
                .then()
                .statusCode(301)
                .header("Location", HOME + "/2026/03/04/" + renamed + "/")
                .header("X-Redirect-By", "QuarkWord");
        calls.visitor().get("/2026/03/04/" + renamed + "/").then().statusCode(200);
    }

    @Test
    void anAttachmentGoesToItsFile() {
        String words = "attached words " + FrontCalls.token();
        long id = calls.upload("notes-" + FrontCalls.token() + ".txt", words);
        JsonPath item = calls.visitor()
                .get("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String file = item.getString("source_url");

        // attachment pages are off by default: the attachment's page and its plain link go to the file
        calls.visitor()
                .get(FrontCalls.pathOf(item.getString("link")))
                .then()
                .statusCode(301)
                .header("Location", file)
                .header("X-Redirect-By", "QuarkWord");
        calls.visitor().get("/?attachment_id=" + id).then().statusCode(301).header("Location", file);
        calls.visitor().get(FrontCalls.pathOf(file)).then().statusCode(200).body(equalTo(words));
    }

    @Test
    void thePostPasswordCookieUnlocksAProtectedPost() throws Exception {
        String slug = "locked-" + FrontCalls.token();
        published(slug, "Locked " + FrontCalls.token(), Map.of("content", "SECRET CONTENT", "password", "s3"));
        String permalink = HOME + "/2026/03/04/" + slug + "/";

        calls.visitor()
                .get(FrontCalls.pathOf(permalink))
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("name=\"post_password\""))
                .body(not(containsString("SECRET CONTENT")));

        Response postpass = calls.visitor()
                .contentType("application/x-www-form-urlencoded")
                .body("post_password=s3&redirect_to=" + URLEncoder.encode(permalink, StandardCharsets.UTF_8))
                .post("/wp-login.php?action=postpass");
        postpass.then().statusCode(302).header("Location", permalink).header("X-Redirect-By", "QuarkWord");
        String name = "wp-postpass_"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("MD5").digest(HOME.getBytes(StandardCharsets.UTF_8)));
        String cookie = FrontCalls.cookiePair(postpass, name);

        calls.visitor()
                .header("Cookie", cookie)
                .get(FrontCalls.pathOf(permalink))
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("SECRET CONTENT"))
                .body(not(containsString("name=\"post_password\"")));
    }

    @Test
    void aPreviewSessionShowsADraft() {
        String title = "Draft " + FrontCalls.token();
        long draft = calls.post("post", "draft", title, Map.of("content", "Draft words."));
        calls.visitor().get("/?p=" + draft).then().statusCode(404);

        String session = calls.previewSession("alice");
        calls.visitor()
                .header("Cookie", session)
                .get("/?p=" + draft)
                .then()
                .statusCode(200)
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Content-Security-Policy", "frame-ancestors 'self'")
                .header("Cache-Control", NO_CACHE)
                .header("Expires", EXPIRES)
                .body(containsString(title))
                .body(containsString("Draft words."));
    }

    @Test
    void plainPermalinksServeTheHomeForAnyPathWithinTheBounds() {
        String title = "Plain " + FrontCalls.token();
        calls.post("post", "publish", title, Map.of());
        calls.textOption("permalink_structure", "");

        calls.visitor()
                .get("/anything/")
                .then()
                .statusCode(200)
                .header("Content-Type", HTML)
                .header("Cache-Control", nullValue())
                .body(containsString(title));
        // a path of 2,048 bytes is still the home; one of 2,049 is outside the request bounds (both end in a slash: the
        // home's path without one is redirected to it, as WordPress's redirect_canonical trailing-slashes the front
        // page)
        String path = "/" + "a".repeat(2046) + "/";
        assertThat(path.getBytes(StandardCharsets.UTF_8)).hasSize(2048);
        calls.visitor().get(path).then().statusCode(200);
        calls.visitor().get("/b" + path.substring(1)).then().statusCode(404).header("Cache-Control", NO_CACHE);
    }
}
