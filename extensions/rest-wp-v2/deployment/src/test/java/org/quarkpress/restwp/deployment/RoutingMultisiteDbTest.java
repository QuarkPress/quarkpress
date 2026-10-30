package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** On a multisite network the REST request's site is the one its host and path resolved to, never another. */
class RoutingMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.multisite(
            RoutingMultisiteDbTest.class,
            TestRoutes.class,
            TestRoutes.PlainResource.class,
            RawHttp.class,
            RawHttp.Reply.class);

    String news;
    long newsId;

    @BeforeEach
    void subdirectorySite() {
        AgroalDataSource data = Jdbc.dataSource();
        news = "news" + UUID.randomUUID().toString().substring(0, 8);
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        newsId = Jdbc.count(data, "select id from qp_site where path = ?", "/" + news + "/");
    }

    @Test
    void aSubdirectorySiteIsAddressedThroughItsOwnPath() {
        for (String url : List.of(
                "/" + news + "/wp-json/qp-test/v1/echo/5",
                "/" + news + "/?rest_route=/qp-test/v1/echo/5",
                "/" + news + "/index.php/wp-json/qp-test/v1/echo/5",
                "/" + news + "/some/page/?rest_route=/qp-test/v1/echo/5")) {
            Http.as(null).get(url).then().statusCode(200).body("site", equalTo((int) newsId));
        }
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/" + news + "/")
                .then()
                .statusCode(200)
                .body("site", equalTo((int) newsId));
        Http.as(null)
                .get("/" + news + "/?rest_route=/qp-test/v1/echo/5")
                .then()
                .body("root", equalTo("http://localhost/" + news + "/wp-json/"))
                .body("home", equalTo("http://localhost/" + news + "/"));
        for (String url : List.of("/wp-json/qp-test/v1/echo/5", "/?rest_route=/qp-test/v1/echo/5")) {
            Http.as(null).get(url).then().statusCode(200).body("site", equalTo(1));
        }
    }

    @Test
    void anotherPrefixIsNoRestPath() {
        Http.as(null)
                .get("/" + news + "-typo/wp-json/qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .body(not(containsString("rest_no_route")));
        // the main site's form field counts at its own path only, not at the subdirectory site's
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/" + news + "/index.php/x")
                .then()
                .statusCode(404)
                .body(not(containsString("urlId")));
    }

    @Test
    void theRouteNeverChoosesTheSite() {
        // rest_route only names a route: the main site answers, and has no such route
        Http.as(null)
                .get("/?rest_route=/" + news + "/wp-json/qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
        Http.as(null)
                .get("/wp-json/" + news + "/wp-json/qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
    }

    @Test
    void pathTricksNeitherReachAnotherSiteNorBypassResolution() {
        for (String target : List.of(
                "/" + news + "/../wp-json/qp-test/v1/echo/5",
                "/wp-json/../" + news + "/wp-json/qp-test/v1/echo/5",
                "//" + news + "/wp-json/qp-test/v1/echo/5",
                "/" + news + "//wp-json/qp-test/v1/echo/5",
                "/" + news + "%2Fwp-json/qp-test/v1/echo/5",
                "/" + news + "/wp-json%2Fqp-test/v1/echo/5",
                "/x/../" + news + "/wp-json/qp-test/v1/echo/5",
                "/" + news + "/./wp-json/qp-test/v1/echo/5")) {
            RawHttp.Reply reply = RawHttp.get(target);
            assertThat(reply.status()).as(target).isIn(400, 404);
            assertThat(reply.text()).as(target).doesNotContain("urlId");
        }
        // an encoded slash below the base stays within the site the path resolved to
        assertThat(RawHttp.get("/" + news + "/wp-json/qp-test%2Fv1/echo/5").text())
                .contains("\"site\":" + newsId);
        // the site path ignores ASCII case, as the site lookup does
        assertThat(RawHttp.get("/" + news.toUpperCase(java.util.Locale.ROOT) + "/wp-json/qp-test/v1/echo/5")
                        .text())
                .contains("\"site\":" + newsId);
    }
}
