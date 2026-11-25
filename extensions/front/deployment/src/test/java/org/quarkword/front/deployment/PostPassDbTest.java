package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.front.runtime.PostPassEndpoint;

/**
 * The {@code postpass} action over HTTP on a single site whose {@code home} and {@code siteurl} carry the test port:
 * the cookie WordPress's six-argument {@code setcookie} writes (its name the MD5 of {@code siteurl}, its value the
 * URL-encoded portable hash, ten days, {@code home}'s path, no {@code HttpOnly}, no {@code SameSite}), the redirect
 * only to the site's own host with {@code admin_url()} as the fallback, the referrer when the form names no target,
 * and the protected post the cookie then unlocks. Plain permalinks; post "Secret recipe" with password {@code s3}.
 */
class PostPassDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(PostPassDbTest.class);

    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String SECRET = "SECRET CONTENT";

    /** {@code name=%24P%24B<rest>; expires=<RFC 1123>; Max-Age=864000; path=/}, nothing else. */
    static final String COOKIE_SHAPE = "=%24P%24B[A-Za-z0-9.%]+; expires=(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2}"
            + " (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} \\d{2}:\\d{2}:\\d{2} GMT; Max-Age=864000;"
            + " path=/";

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static String host;
    static String home;
    static String name;
    static PostId locked;
    static String permalink;

    @BeforeEach
    void cast() throws Exception {
        if (locked != null) {
            return;
        }
        host = "localhost:" + RestAssured.port;
        home = "http://" + host;
        TestData.textOption(dataSource, TestData.MAIN, "home", home);
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", home);
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "");
        TestData.textOption(dataSource, TestData.MAIN, "blogname", "Postpass test");
        locked = TestData.post(
                dataSource,
                TestData.MAIN,
                "post",
                "publish",
                "secret-recipe",
                null,
                null,
                "2026-06-01T10:00:00Z",
                false);
        Jdbc.update(
                dataSource,
                "update qw_post set title = 'Secret recipe', content = ?, password = 's3' where id = ?",
                SECRET,
                locked.value());
        permalink = home + "/?p=" + locked.value();
        name = "wp-postpass_"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("MD5").digest(home.getBytes(StandardCharsets.UTF_8)));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static RequestSpecification http() {
        return Http.at(host, null);
    }

    static Response postpass(String form) {
        return postpass(http(), form);
    }

    static Response postpass(RequestSpecification spec, String form) {
        return spec.contentType("application/x-www-form-urlencoded").body(form).post("/wp-login.php?action=postpass");
    }

    /** The {@code name=value} pair of the answer's post-password cookie. */
    static String pair(Response response) {
        String line = postPassCookie(response);
        return line.substring(0, line.indexOf(';'));
    }

    static String postPassCookie(Response response) {
        List<String> lines = response.headers().getValues("Set-Cookie");
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    @Test
    void theCookieIsSetAndTheVisitorSentBack() {
        Response response = postpass("post_password=s3&redirect_to=" + encode(permalink));
        response.then()
                .statusCode(302)
                .header("Location", permalink)
                .header("X-Redirect-By", "QuarkWord")
                .header("Cache-Control", NO_CACHE)
                .header("Expires", EXPIRES)
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Referrer-Policy", "strict-origin-when-cross-origin")
                .body(emptyString());
        String cookie = postPassCookie(response);
        assertThat(cookie).matches(name + COOKIE_SHAPE);
        assertThat(cookie.toLowerCase()).doesNotContain("httponly", "samesite", "secure", "domain");
        String value = URLDecoder.decode(pair(response).substring(name.length() + 1), StandardCharsets.UTF_8);
        assertThat(value).hasSize(34).startsWith("$P$B");
    }

    @Test
    void anHttpsTargetMakesTheCookieSecure() {
        Response response =
                postpass("post_password=s3&redirect_to=" + encode("https://" + host + "/?p=" + locked.value()));
        response.then().statusCode(302).header("Location", "https://" + host + "/?p=" + locked.value());
        assertThat(postPassCookie(response)).matches(name + COOKIE_SHAPE + "; secure");
    }

    @Test
    void theCookieUnlocksThePostAndAWrongOneShowsTheNotice() {
        http().get("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("class=\"post-password-form\""), not(containsString(SECRET)));
        String right = pair(postpass("post_password=s3&redirect_to=" + encode(permalink)));
        http().header("Cookie", right)
                .get("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(containsString(SECRET), not(containsString("post-password-form")));
        // the cookie as PHP decodes it works too
        String decoded = name + "=" + URLDecoder.decode(right.substring(name.length() + 1), StandardCharsets.UTF_8);
        http().header("Cookie", decoded).get("/?p=" + locked.value()).then().body(containsString(SECRET));
        String wrong = pair(postpass("post_password=wrong&redirect_to=" + encode(permalink)));
        http().header("Cookie", wrong)
                .header("Referer", permalink)
                .get("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(
                        containsString("class=\"post-password-form password-form-error\""),
                        containsString("<p id=\"error-pwbox-" + locked.value() + "\">Invalid password.</p>"),
                        not(containsString(SECRET)));
        // without the referrer the form has no notice
        http().header("Cookie", wrong)
                .get("/?p=" + locked.value())
                .then()
                .body(containsString("class=\"post-password-form\""), not(containsString("Invalid password.")));
        // a HEAD request shows nothing either
        http().header("Cookie", wrong)
                .head("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(emptyString());
    }

    @Test
    void redirectToOtherHostFallsBack() {
        for (String target : new String[] {"https://evil.test/", "//evil.test/x", "javascript:alert(1)"}) {
            Response response = postpass("post_password=s3&redirect_to=" + encode(target));
            response.then().statusCode(302).header("Location", home + "/wp-admin/");
            assertThat(postPassCookie(response)).as(target).startsWith(name + "=");
        }
        // an https target on another host still makes the cookie secure, as in WordPress
        assertThat(postPassCookie(postpass("post_password=s3&redirect_to=" + encode("https://evil.test/"))))
                .endsWith("; secure");
    }

    @Test
    void withoutATargetTheReferrerIsUsed() {
        postpass(http().header("Referer", permalink), "post_password=s3")
                .then()
                .statusCode(302)
                .header("Location", permalink);
        // a referrer on another host is no target: nothing to redirect to
        postpass(http().header("Referer", "https://evil.test/"), "post_password=s3")
                .then()
                .statusCode(200)
                .header("Location", nullValue())
                .body(emptyString());
        // the form's own address is no target either
        postpass(http().header("Referer", home + "/wp-login.php?action=postpass"), "post_password=s3")
                .then()
                .statusCode(200)
                .header("Location", nullValue());
        // an empty field is a target: WordPress takes it, so no redirect and no referrer
        postpass(http().header("Referer", permalink), "post_password=s3&redirect_to=")
                .then()
                .statusCode(200)
                .header("Location", nullValue());
        Response none = postpass("post_password=s3");
        none.then().statusCode(200).header("Location", nullValue()).body(emptyString());
        assertThat(postPassCookie(none)).startsWith(name + "=");
    }

    /** {@code count} form pairs {@code v0=1&v1=1&…}. */
    static String variables(int count) {
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < count; i++) {
            form.append('v').append(i).append("=1&");
        }
        return form.toString();
    }

    @Test
    void aFormKeepsPhpsThousandAndOneVariables() {
        // PHP registers the first max_input_vars + 1 pairs of an urlencoded form and drops the rest (php -S probe)
        Response kept = postpass(http().header("Referer", permalink), variables(1000) + "post_password=s3");
        kept.then().statusCode(302).header("Location", permalink);
        assertThat(postPassCookie(kept)).startsWith(name + "=");
        Response dropped = postpass(http().header("Referer", permalink), variables(1001) + "post_password=s3");
        dropped.then().statusCode(302).header("Location", permalink);
        assertThat(dropped.headers().getValues("Set-Cookie")).isEmpty();
    }

    @Test
    void anOversizedFormIsReadAsPhpReadsOneOverItsPostLimit() {
        // over the bound the form is not read, as PHP leaves $_POST empty past post_max_size: no cookie, the query's
        // action and the referrer still count
        String padding = "&pad=" + "a".repeat(PostPassEndpoint.MAX_FORM_BYTES);
        Response response = postpass(
                http().header("Referer", permalink),
                "post_password=s3&redirect_to=" + encode(home + "/elsewhere/") + padding);
        response.then().statusCode(302).header("Location", permalink).body(emptyString());
        assertThat(response.headers().getValues("Set-Cookie")).isEmpty();
        // the action only in the oversized form: no postpass request
        Response formAction = http().contentType("application/x-www-form-urlencoded")
                .body("action=postpass&post_password=s3" + padding)
                .post("/wp-login.php");
        formAction.then().statusCode(404);
        assertThat(formAction.headers().getValues("Set-Cookie")).isEmpty();
        // at the bound the form is read
        String form = "post_password=s3&redirect_to=" + encode(permalink) + "&pad=";
        Response atBound = postpass(form + "a".repeat(PostPassEndpoint.MAX_FORM_BYTES - form.length()));
        atBound.then().statusCode(302).header("Location", permalink);
        assertThat(postPassCookie(atBound)).startsWith(name + "=");
    }

    @Test
    void anArrayRefererFieldGivesWayToTheHeader() {
        postpass(
                        http().header("Referer", permalink),
                        "post_password=s3&_wp_http_referer%5B%5D=" + encode(home + "/elsewhere/"))
                .then()
                .statusCode(302)
                .header("Location", permalink);
    }

    @Test
    void withoutAPasswordOnlyTheRedirect() {
        Response response = postpass("redirect_to=" + encode(permalink));
        response.then().statusCode(302).header("Location", permalink);
        assertThat(response.headers().getValues("Set-Cookie")).isEmpty();
    }

    @Test
    void theActionMayComeFromTheForm() {
        Response response = http().contentType("application/x-www-form-urlencoded")
                .body("action=postpass&post_password=s3&redirect_to=" + encode(permalink))
                .post("/wp-login.php");
        response.then().statusCode(302).header("Location", permalink);
        assertThat(postPassCookie(response)).startsWith(name + "=");
    }

    @Test
    void aReadOfTheActionOnlyRedirects() {
        // WordPress runs the action for any method: without a POST field nothing is set, the referrer is the target
        Response get = http().get("/wp-login.php?action=postpass&post_password=s3");
        get.then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Location", nullValue())
                .body(emptyString());
        assertThat(get.headers().getValues("Set-Cookie")).isEmpty();
        http().header("Referer", permalink)
                .get("/wp-login.php?action=postpass")
                .then()
                .statusCode(302)
                .header("Location", permalink);
        http().header("Referer", permalink)
                .head("/wp-login.php?action=postpass")
                .then()
                .statusCode(302)
                .header("Location", permalink)
                .body(emptyString());
        // the login page itself is not served
        http().get("/wp-login.php").then().statusCode(404).body(emptyString());
        http().get("/wp-login.php?action=login").then().statusCode(404);
    }

    @Test
    void onlyPostpassOnTheSitesPathIsServed() {
        for (String path : new String[] {
            "/wp-login.php",
            "/wp-login.php?action=login",
            "/wp-login.php?action=postpass&key=x",
            "/blog/wp-login.php?action=postpass"
        }) {
            Response response = http().contentType("application/x-www-form-urlencoded")
                    .body("post_password=s3&redirect_to=" + encode(permalink))
                    .post(path);
            response.then().statusCode(404);
            assertThat(response.headers().getValues("Set-Cookie")).as(path).isEmpty();
        }
    }

    @Test
    void whereWordPressFailsTheAnswerIs500WithoutACookie() {
        // a redirect_to array: PHP's parse_url throws before anything is set
        Response array = postpass("post_password=s3&redirect_to%5B%5D=" + encode(permalink));
        array.then().statusCode(500).header("Location", nullValue()).body(emptyString());
        assertThat(array.headers().getValues("Set-Cookie")).isEmpty();
        // a home path PHP's setcookie refuses (it throws on a space or a semicolon in the path)
        TestData.textOption(dataSource, TestData.MAIN, "home", home + "/a;b");
        try {
            Response refused = postpass("post_password=s3&redirect_to=" + encode(permalink));
            refused.then().statusCode(500).header("Location", nullValue());
            assertThat(refused.headers().getValues("Set-Cookie")).isEmpty();
        } finally {
            TestData.textOption(dataSource, TestData.MAIN, "home", home);
        }
    }

    @Test
    void theRedirectTargetIsCleanedAsWordPressCleansIt() {
        // the field is sanitised before it is validated: spaces around an address make it a relative path
        postpass("post_password=s3&redirect_to=" + encode(" " + permalink + " "))
                .then()
                .statusCode(302)
                .header("Location", "/%20http://" + host + "/?p=" + locked.value() + "%20");
        // CR and LF never reach the header; a NUL becomes the 0 of its slashed form
        postpass("post_password=s3&redirect_to=" + encode(home + "/a\r\nSet-Cookie: x=1"))
                .then()
                .statusCode(302)
                .header("Location", home + "/aSet-Cookie:%20x=1");
        postpass("post_password=s3&redirect_to=" + encode(home + "/a\0b"))
                .then()
                .statusCode(302)
                .header("Location", home + "/a0b");
    }
}
