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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.UserId;

/**
 * The REST module without the front module: no front-end request is answered by QuarkWord (the server's own 404, no
 * redirect, none of the front end's headers), while the REST API and the preview session route work, the cookie's
 * lifetime and {@code expires_in} following {@code quarkword.posts.preview-lifetime} (30 minutes here). This application
 * trusts no proxy, so a client's {@code X-Forwarded-Proto: https} never makes the cookie {@code Secure}.
 */
class WithoutFrontDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    WithoutFrontDbTest.class, PostFixtures.class, PostFixtures.Builder.class, TestClock.class)
            .overrideConfigKey("quarkword.posts.preview-lifetime", "30m");

    static AgroalDataSource data;
    static UserId carol;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        carol = TestData.memberOfMain(data, "author");
    }

    @Test
    void noFrontEndRequestIsAnswered() {
        PostId published = PostFixtures.published(data, TestData.MAIN, carol, "Off title", "2026-01-10T10:00:00Z");
        Response answer = Http.as(null).get("/?p=" + published.value());
        answer.then().statusCode(404);
        assertThat(answer.header("X-Redirect-By")).isNull();
        assertThat(answer.header("Expires")).isNull();
        assertThat(answer.headers().getValues("Link")).isEmpty();
        assertThat(answer.asString()).doesNotContain("Off title");
        Http.as(null).get("/?p=" + published.value() + "&preview=true").then().statusCode(404);
        Http.as(null).get("/index.php?p=" + published.value()).then().statusCode(404);
        // the former internal page resource is gone, and the front module's is absent
        Http.as(null)
                .get("/_quarkword/front-end/page?p=" + published.value())
                .then()
                .statusCode(404);
        Http.as(null)
                .get("/_quarkword/front/page?p=" + published.value())
                .then()
                .statusCode(404);
        // the REST API answers as before
        Http.as(null)
                .get("/wp-json/wp/v2/posts/" + published.value())
                .then()
                .statusCode(200)
                .body("title.rendered", equalTo("Off title"));
        Http.as(null)
                .get("/?rest_route=/wp/v2/posts/" + published.value())
                .then()
                .statusCode(200)
                .body("id", equalTo((int) published.value()));
    }

    @Test
    void theSessionLifetimeFollowsTheConfiguration() {
        Http.as("test-" + carol.value())
                .post("/wp-json/quarkword/v1/preview/session")
                .then()
                .statusCode(200)
                .header(
                        "Set-Cookie",
                        allOf(
                                startsWith("quarkword_preview="),
                                containsString("Max-Age=1800"),
                                containsString("Path=/")))
                .body("expires_in", equalTo(1800));
        // without quarkus.http.proxy.* trusting a proxy, the forwarded scheme is a client header and nothing more
        Http.as("test-" + carol.value())
                .header("X-Forwarded-Proto", "https")
                .post("/wp-json/quarkword/v1/preview/session")
                .then()
                .statusCode(200)
                .header("Set-Cookie", not(containsString("Secure")));
    }
}
