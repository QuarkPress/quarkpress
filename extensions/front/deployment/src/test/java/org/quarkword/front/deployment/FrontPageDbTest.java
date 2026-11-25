package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;

/**
 * Front-end requests over HTTP through the catch-all route, the page resource and the fallback page: a site of its own
 * (looked up by its domain) whose {@code home} and {@code siteurl} carry the test port, as a request on a port {@code
 * home} lacks is redirected to the port-less URL; the default permalink structure and ten posts a page. Fifteen
 * published posts: hello-world (2025-11-20, "Hello world!"), news-item (2025-11-19, in News) and thirteen fillers.
 */
class FrontPageDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(FrontPageDbTest.class, CountingPosts.class);

    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String HTML = "text/html; charset=UTF-8";

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static SiteId site;
    static String host;
    static String home;
    static PostId hello;
    static PostId newsItem;

    @BeforeEach
    void cast() {
        if (site != null) {
            return;
        }
        site = TestData.site(dataSource);
        host = TestData.domain(dataSource, site) + ":" + RestAssured.port;
        home = "http://" + host;
        TestData.textOption(dataSource, site, "home", home);
        TestData.textOption(dataSource, site, "siteurl", home);
        TestData.textOption(dataSource, site, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
        TestData.textOption(dataSource, site, "blogname", "Front test");
        TestData.siteOption(dataSource, site, "posts_per_page", "10");
        hello = post("hello-world", "Hello world!", "2025-11-20T10:15:30Z");
        Jdbc.update(dataSource, "update qw_post set content = 'Welcome words' where id = ?", hello.value());
        newsItem = post("news-item", "News item", "2025-11-19T10:00:00Z");
        TermId news = TestData.term(dataSource, site, "category", "News", "news", null);
        TestData.assign(dataSource, site, newsItem, news);
        for (int i = 1; i <= 13; i++) {
            post("filler-" + i, "Filler " + i, String.format("2025-10-%02dT10:00:00Z", i));
        }
    }

    private PostId post(String slug, String title, String date) {
        PostId post = TestData.post(dataSource, site, "post", "publish", slug, null, null, date, false);
        Jdbc.update(dataSource, "update qw_post set title = ? where id = ?", title, post.value());
        return post;
    }

    private static RequestSpecification page() {
        return Http.at(host, null);
    }

    @Test
    void aPostIsServedWithWordPresssHeaders() {
        Response response = page().get("/2025/11/20/hello-world/");
        response.then()
                .statusCode(200)
                .contentType(HTML)
                .header("Cache-Control", nullValue())
                .header("Expires", nullValue())
                .header("X-Pingback", nullValue())
                .body(containsString("<h1 class=\"entry-title\">Hello world!</h1>"))
                .body(containsString("<title>Hello world! &#8211; Front test</title>"))
                .body(containsString("<time datetime=\"2025-11-20T10:15:30+00:00\">November 20, 2025</time>"))
                .body(containsString("Welcome words"))
                .body(containsString("<body class=\"single single-post postid-" + hello.value()));
        assertThat(response.headers().getValues("Link"))
                .containsExactly(
                        "<" + home + "/wp-json/>; rel=\"https://api.w.org/\"",
                        "<" + home + "/wp-json/wp/v2/posts/" + hello.value()
                                + ">; rel=\"alternate\"; title=\"JSON\"; type=\"application/json\"",
                        "<" + home + "/?p=" + hello.value() + ">; rel=shortlink");
    }

    @Test
    void aHeadRequestGetsTheSameHeadersAndNoBody() {
        Response get = page().get("/2025/11/20/hello-world/");
        Response head = page().head("/2025/11/20/hello-world/");
        head.then().statusCode(200).contentType(HTML).body(emptyString());
        assertThat(comparable(head)).isEqualTo(comparable(get)).containsKey("link");
        Response missingGet = page().get("/nope/");
        Response missingHead = page().head("/nope/");
        missingHead
                .then()
                .statusCode(404)
                .header("Expires", EXPIRES)
                .header("Cache-Control", NO_CACHE)
                .body(emptyString());
        assertThat(comparable(missingHead)).isEqualTo(comparable(missingGet));
    }

    /** The response's headers by lower-case name with their values in order, without Date and Content-Length. */
    private static Map<String, List<String>> comparable(Response response) {
        Map<String, List<String>> headers = new TreeMap<>();
        for (Header header : response.headers()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("date") && !name.equals("content-length")) {
                headers.computeIfAbsent(name, key -> new ArrayList<>()).add(header.getValue());
            }
        }
        return headers;
    }

    @Test
    void aMissingPageIs404WithTheNoCacheHeaders() {
        page().get("/nope/")
                .then()
                .statusCode(404)
                .contentType(HTML)
                .header("Expires", EXPIRES)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("<body class=\"error404\">"))
                .body(containsString("<h1 class=\"entry-title\">Page not found</h1>"))
                .body(containsString("<title>Page not found &#8211; Front test</title>"));
        // a page the front end does not serve yet (a feed) is a 404 too
        page().get("/feed/")
                .then()
                .statusCode(404)
                .header("Cache-Control", NO_CACHE)
                .body(containsString("<body class=\"error404\">"));
    }

    @Test
    void aPlainLinkIsRedirectedToThePermalink() {
        page().get("/?p=" + hello.value())
                .then()
                .statusCode(301)
                .header("Location", home + "/2025/11/20/hello-world/")
                .header("X-Redirect-By", "QuarkWord")
                .body(emptyString());
    }

    @Test
    void aCategoryListsItsPosts() {
        page().get("/category/news/")
                .then()
                .statusCode(200)
                .body(containsString("<body class=\"archive\">"))
                .body(containsString("<li><a href=\"" + home + "/2025/11/19/news-item/\">News item</a></li>"))
                .body(not(containsString("hello-world")));
    }

    @Test
    void theHomeListIsPaged() {
        page().get("/")
                .then()
                .statusCode(200)
                .body(containsString("<body class=\"home blog\">"))
                .body(containsString("<a class=\"next\" href=\"" + home + "/page/2/\">"))
                .body(not(containsString("class=\"prev\"")));
        page().get("/page/2/")
                .then()
                .statusCode(200)
                .body(containsString("<a class=\"prev\" href=\"" + home + "/\">"))
                .body(not(containsString("class=\"next\"")));
    }

    @Test
    void otherMethodsAreNotAnsweredByTheFrontEnd() {
        Response post = page().post("/2025/11/20/hello-world/");
        assertThat(post.statusCode()).isEqualTo(404);
        assertThat(post.header("X-Redirect-By")).isNull();
        assertThat(post.header("Expires")).isNull();
        assertThat(post.headers().getValues("Link")).isEmpty();
        Response put = page().put("/");
        assertThat(put.statusCode()).isIn(404, 405);
        assertThat(put.headers().getValues("Link")).isEmpty();
    }

    @Test
    void theNonApplicationRootIsLeftAlone() {
        Response answer = page().get("/q/nothing-here");
        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.headers().getValues("Link")).isEmpty();
        assertThat(answer.header("Expires")).isNull();
    }

    @Test
    void hostilePathsAnswer404WithoutQueries() {
        String longPath = "/" + "a".repeat(2100) + "/";
        String deep = "/a".repeat(33) + "/";
        StringBuilder many = new StringBuilder("/?");
        for (int i = 0; i < 65; i++) {
            many.append(i == 0 ? "" : "&").append("v").append(i).append("=1");
        }
        for (String target : List.of(longPath, deep, "/%C3/", many.toString(), "/?s=%C3")) {
            CountingPosts.QUERIES.set(0);
            Response answer = page().urlEncodingEnabled(false).get(target);
            assertThat(answer.statusCode()).as(target).isEqualTo(404);
            assertThat(answer.header("Expires")).as(target).isEqualTo(EXPIRES);
            assertThat(answer.header("X-Redirect-By")).as(target).isNull();
            assertThat(answer.asString()).as(target).contains("<h1 class=\"entry-title\">Page not found</h1>");
            assertThat(CountingPosts.QUERIES.get()).as(target).isZero();
        }
        // a dot segment: the multisite module serves such a path without a site (an empty 404) before the front end
        // would refuse it
        CountingPosts.QUERIES.set(0);
        page().urlEncodingEnabled(false)
                .get("/x/%2E%2E/y/")
                .then()
                .statusCode(404)
                .header("X-Redirect-By", nullValue());
        assertThat(CountingPosts.QUERIES.get()).isZero();
        // a request within the bounds does query
        CountingPosts.QUERIES.set(0);
        page().get("/nope/").then().statusCode(404);
        assertThat(CountingPosts.QUERIES.get()).isPositive();
    }

    @Test
    void anErrorThatEndsTheRequestHasNoBody() {
        // the query string's error is read in plain mode only
        TestData.textOption(dataSource, site, "permalink_structure", "");
        try {
            page().get("/?error=403")
                    .then()
                    .statusCode(403)
                    .contentType(HTML)
                    .header("Location", nullValue())
                    .body(emptyString());
        } finally {
            TestData.textOption(dataSource, site, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
        }
        page().get("/?error=403").then().statusCode(200).body(containsString("<body class=\"home blog\">"));
        assertThat(page().get("/2025/11/20/hello-world/").header("X-Redirect-By"))
                .isNull();
        page().get("/2025/11/20/hello-world/").then().header("Content-Type", equalTo(HTML));
    }
}
