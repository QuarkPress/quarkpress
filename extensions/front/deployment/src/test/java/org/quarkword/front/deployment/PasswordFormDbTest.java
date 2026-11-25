package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
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
 * The password form on the fallback page over HTTP: a protected post shows WordPress's form (never its content) with
 * the no-cache set, to every visitor, previews included; anonymous searches leave protected posts out, a logged-in
 * visitor's include them (titles only). A site of its own with plain permalinks, {@code home} carrying the test port;
 * members alice (administrator) and erin (subscriber).
 */
class PasswordFormDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(PasswordFormDbTest.class);

    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String SECRET = "SECRET CONTENT";

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static String home;
    static UserId alice;
    static UserId erin;
    static PostId locked;
    static PostId hidden;

    @BeforeEach
    void cast() {
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
        TestData.textOption(data, site, "blogname", "Form test");
        alice = member("alice", "administrator");
        erin = member("erin", "subscriber");
        locked = TestData.post(
                data, site, "post", "publish", "secret-recipe", alice, null, "2026-06-01T10:00:00Z", false);
        Jdbc.update(
                data,
                "update qw_post set title = 'Secret recipe', content = ?, excerpt = 'SECRET EXCERPT', password = 's3'"
                        + " where id = ?",
                SECRET,
                locked.value());
        PostId open =
                TestData.post(data, site, "post", "publish", "open-post", alice, null, "2026-05-01T10:00:00Z", false);
        Jdbc.update(data, "update qw_post set title = 'Open secret notes' where id = ?", open.value());
        hidden = TestData.post(
                data, site, "post", "private", "private-notes", alice, null, "2026-04-01T10:00:00Z", false);
        Jdbc.update(data, "update qw_post set title = 'Private notes' where id = ?", hidden.value());
    }

    private static UserId member(String name, String role) {
        UserId user = TestData.profile(data, name, name + "-" + UUID.randomUUID());
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user;
    }

    private static RequestSpecification page(String session) {
        RequestSpecification spec = Http.at(host, null);
        return session == null ? spec : spec.cookie(PreviewViewer.COOKIE, session);
    }

    /** WordPress's classic-theme form for the locked post, as the page prints it inside the content's place. */
    private static String form() {
        long id = locked.value();
        return "<div class=\"entry-content\"><form action=\"" + home + "/wp-login.php?action=postpass\""
                + " class=\"post-password-form\" method=\"post\"><input type=\"hidden\" name=\"redirect_to\" value=\""
                + home + "/?p=" + id + "\" />\n\t<p>This content is password-protected. To view it, please enter the"
                + " password below.</p>\n\t<p><label for=\"pwbox-" + id + "\">Password: <input name=\"post_password\""
                + " id=\"pwbox-" + id + "\" type=\"password\" spellcheck=\"false\" required size=\"20\" /></label>"
                + " <input type=\"submit\" name=\"Submit\" value=\"Enter\" /></p></form>\n\t</div>";
    }

    @Test
    void aProtectedPostShowsTheFormWithTheNoCacheSet() {
        Response response = page(null).get("/?p=" + locked.value());
        response.then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .header("Expires", EXPIRES)
                .body(
                        containsString(form()),
                        containsString("<h1 class=\"entry-title\">Protected: Secret recipe</h1>"),
                        containsString("<title>Secret recipe &#8211; Form test</title>"),
                        not(containsString(SECRET)),
                        not(containsString("SECRET EXCERPT")),
                        not(containsString("s3")));
        page(null)
                .head("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .header("Cache-Control", NO_CACHE)
                .body(emptyString());
    }

    @Test
    void aLoggedInVisitorSeesTheFormToo() throws Exception {
        page(session(alice))
                .get("/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(containsString(form()), not(containsString(SECRET)));
    }

    @Test
    void anonymousSearchesLeaveProtectedPostsOut() throws Exception {
        String anonymous = page(null).get("/?s=secret").asString();
        assertThat(anonymous).contains("Open secret notes").doesNotContain("Secret recipe");
        String member = page(session(erin)).get("/?s=secret").asString();
        assertThat(member)
                .contains("Open secret notes", ">Protected: Secret recipe</a>")
                .doesNotContain(SECRET);
    }

    @Test
    void aPrivatePostsEntryTitleIsPrefixedAndItsDocumentTitleIsNot() throws Exception {
        page(session(alice))
                .get("/?p=" + hidden.value())
                .then()
                .statusCode(200)
                .body(
                        containsString("<h1 class=\"entry-title\">Private: Private notes</h1>"),
                        containsString("<title>Private notes &#8211; Form test</title>"));
    }

    @Test
    void previewDoesNotLiftThePassword() throws Exception {
        String token = Identities.onSite(
                site,
                Identities.user(alice),
                () -> Arc.container().instance(PreviewTokens.class).get().issue(locked, alice));
        page(session(alice))
                .get("/?p=" + locked.value() + "&preview_id=" + locked.value() + "&preview_nonce=" + token
                        + "&preview=true")
                .then()
                .statusCode(200)
                .body(containsString("post-password-form"), not(containsString(SECRET)));
    }

    /** A fresh preview session of the user on the site. */
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
}
