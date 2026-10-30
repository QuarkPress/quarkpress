package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RoutingDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.with(
            RoutingDbTest.class, TestRoutes.class, TestRoutes.PlainResource.class, RawHttp.class, RawHttp.Reply.class);

    @Test
    void everyUrlFormReachesTheRoute() {
        for (String url : List.of(
                "/wp-json/qp-test/v1/echo/5",
                "/wp-json/QP-TEST/V1/ECHO/5/",
                "/index.php/wp-json/qp-test/v1/echo/5",
                "/?rest_route=/qp-test/v1/echo/5/",
                "/some/page/?rest_route=/qp-test/v1/echo/5",
                "/index.php?rest_route=/qp-test/v1/echo/5")) {
            Http.as(null).get(url).then().statusCode(200).body("urlId", equalTo("5"));
        }
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/")
                .then()
                .statusCode(200)
                .body("method", equalTo("POST"));
    }

    @Test
    void hrefsUseTheSiteAddressAndTheBase() {
        Http.as(null)
                .get("/?rest_route=/qp-test/v1/echo/5")
                .then()
                .body("root", equalTo("http://localhost/wp-json/"))
                .body("link", equalTo("http://localhost/wp-json/qp-test/v1/items?a+b=c%26d%2F%C3%A9"))
                .body("home", equalTo("http://localhost/"));
    }

    @Test
    void nonRestPathsAreNotServed() {
        // passed on to the application's own routes (none here), never answered by the REST server
        Http.as(null)
                .get("/foo/wp-json/qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .header("Link", nullValue())
                .body(not(containsString("rest_no_route")));
        Http.as(null)
                .get("/_quarkpress/rest-wp-v2/qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .body(emptyString());
        Http.as(null)
                .get("/?rest_route=//qp-test/v1/echo/5")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
    }

    @Test
    void theNonApplicationRootAndOtherApplicationRoutesAreLeftAlone() {
        // TestRoutes' app also has a plain JAX-RS resource GET /it-plain/x/wp-json/ping answering "pong"
        Http.as(null)
                .get("/q/health?rest_route=/")
                .then()
                .header("Link", nullValue())
                .body(not(containsString("rest_no_route")));
        Http.as(null).get("/it-plain/x/wp-json/ping").then().statusCode(200).body(equalTo("pong"));
        // an empty rest_route ("" or "0" to PHP) leaves the request to the application's own route
        Http.as(null)
                .get("/it-plain/x/wp-json/ping?rest_route=0")
                .then()
                .statusCode(200)
                .body(equalTo("pong"));
        Http.as(null)
                .get("/it-plain/x/wp-json/ping?rest_route=")
                .then()
                .statusCode(200)
                .body(equalTo("pong"));
    }

    @Test
    void hostileRouteParametersAreRefusedLikeWordPress() {
        // Part D 2: WordPress's wp_die page (no JSON Accept here; Task 10 adds the JSON form), no-cache, no REST
        // headers
        for (String url : List.of("/?rest_route[]=x", "/wp-json/qp-test/v1?rest_route[]=x")) {
            Http.as(null)
                    .get(url)
                    .then()
                    .statusCode(400)
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                    .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private")
                    .header("Link", nullValue())
                    .header("X-Robots-Tag", nullValue())
                    .body(containsString("The REST route parameter must be a string."));
        }
    }

    @Test
    void parametersFollowWordPresssPrecedence() {
        Http.as(null)
                .get("/wp-json/qp-test/v1/echo/5?id=7")
                .then()
                .body("id", equalTo("7"))
                .body("urlId", equalTo("5"));
        Http.as(null)
                .contentType("application/vnd.example+json; charset=utf-8")
                .body("{\"id\":\"9\"}")
                .post("/wp-json/qp-test/v1/echo/5?id=7")
                .then()
                .body("id", equalTo("9"));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("id=8")
                .get("/wp-json/qp-test/v1/echo/5")
                .then()
                .body("id", equalTo("5"));
    }

    @Test
    void methodOverrideAppliesToAnyMethodQueryFirst() {
        Http.as(null).get("/wp-json/qp-test/v1/echo/5?_method=post").then().body("method", equalTo("POST"));
        Http.as(null)
                .header("X-HTTP-Method-Override", "POST")
                .get("/wp-json/qp-test/v1/echo/5")
                .then()
                .body("method", equalTo("POST"));
        Http.as(null)
                .header("X-HTTP-Method-Override", "DELETE")
                .get("/wp-json/qp-test/v1/echo/5?_method=POST")
                .then()
                .body("method", equalTo("POST"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/echo/5?_method=FOO")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
        Http.as(null)
                .contentType("application/json")
                .body("{\"_method\":\"POST\"}")
                .get("/wp-json/qp-test/v1/echo/5")
                .then()
                .body("method", equalTo("GET"));
    }

    @Test
    void anUnsupportedMethodIsNoRouteAndHeadFallsBackToGet() {
        Http.as(null)
                .delete("/wp-json/qp-test/v1/items")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"))
                .body("message", equalTo("No route was found matching the URL and request method."))
                .body("data.status", equalTo(404));
        Http.as(null).head("/wp-json/qp-test/v1/items").then().statusCode(200);
        RawHttp.Reply head = RawHttp.send(
                "HEAD /wp-json/qp-test/v1/echo/5 HTTP/1.1\r\nHost: localhost\r\n" + "Connection: close\r\n\r\n");
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.text()).endsWith("\r\n\r\n").doesNotContain("urlId");
        Http.as(null).get("/wp-json/qp-test/v1/nothing").then().statusCode(404).body("code", equalTo("rest_no_route"));
    }

    @Test
    void theEditorsRequestShapesReachTheirRoutes() {
        // the block editor asks for collections with a trailing slash and adds _locale=user to every request
        Http.as(null)
                .get("/wp-json/qp-test/v1/items/?context=edit&_locale=user")
                .then()
                .statusCode(200)
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(equalTo("[]"));
        Http.as(null)
                .urlEncodingEnabled(false)
                .get("/?rest_route=%2Fqp-test%2Fv1%2Fitems%2F&_locale=user")
                .then()
                .statusCode(200)
                .body(equalTo("[]"));
        Http.as(null)
                .get("/wp-json/qp-test/v1/echo/5?_locale=user")
                .then()
                .statusCode(200)
                .body("params._locale", equalTo("user"));
    }

    @Test
    void theRestRouteOfAFormPostIsCheckedAgainstTheQuery() {
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/index.php")
                .then()
                .statusCode(200)
                .body("method", equalTo("POST"));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/?rest_route=/qp-test/v1/echo/5")
                .then()
                .statusCode(200)
                .body("urlId", equalTo("5"));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/?rest_route=/qp-test/v1/items")
                .then()
                .statusCode(400)
                .header("Content-Type", "text/html; charset=UTF-8")
                .body(containsString("A variable mismatch has been detected."));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/?rest_route=")
                .then()
                .statusCode(400)
                .body(containsString("A variable mismatch has been detected."));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route[]=x")
                .post("/")
                .then()
                .statusCode(400)
                .body(containsString("The REST route parameter must be a string."));
        // a form POST to the site path without rest_route is no REST request
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("a=1")
                .post("/")
                .then()
                .statusCode(404)
                .body(emptyString());
        // the form field counts only at the site path
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/some/page/")
                .then()
                .statusCode(404)
                .body(not(containsString("urlId")));
        // an empty rest_route in the query is no REST request either
        Http.as(null)
                .get("/wp-json/qp-test/v1/echo/5?rest_route=")
                .then()
                .statusCode(404)
                .body(not(containsString("urlId")));
        Http.as(null).get("/?rest_route=0").then().statusCode(404).body(not(containsString("rest_no_route")));
    }

    @Test
    void theInternalPathCannotBeReachedDirectly() {
        for (String target : List.of(
                "/_quarkpress/rest-wp-v2/route",
                "/_quarkpress/rest-wp-v2",
                "//_quarkpress/rest-wp-v2/route",
                "/_quarkpress/./rest-wp-v2/route",
                "/x/../_quarkpress/rest-wp-v2/route",
                "/_quarkpress%2Frest-wp-v2/route",
                "/_QUARKPRESS/rest-wp-v2/route")) {
            RawHttp.Reply reply = RawHttp.get(target);
            assertThat(reply.status()).as(target).isIn(400, 404);
            assertThat(reply.text()).as(target).doesNotContain("urlId").doesNotContain("rest_no_route");
        }
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/5")
                .post("/_quarkpress/rest-wp-v2/route")
                .then()
                .statusCode(404)
                .body(emptyString());
    }

    @Test
    void pathTricksAreNeverRestPathsOfAnotherPrefix() {
        // the base right after the site's path, on the raw path: no double slash, dot segment or encoded slash
        for (String target : List.of(
                "//wp-json/qp-test/v1/echo/5",
                "/x/../wp-json/qp-test/v1/echo/5",
                "/./wp-json/qp-test/v1/echo/5",
                "/x%2F..%2Fwp-json/qp-test/v1/echo/5",
                "/wp-json%2Fqp-test/v1/echo/5",
                "/%77p-json/qp-test/v1/echo/5")) {
            RawHttp.Reply reply = RawHttp.get(target);
            assertThat(reply.status()).as(target).isIn(400, 404);
            assertThat(reply.text()).as(target).doesNotContain("urlId").doesNotContain("rest_no_route");
        }
        // below the base, the route is decoded once and matched as it is
        assertThat(RawHttp.get("/wp-json/qp-test%2Fv1/echo/5").text()).contains("\"urlId\":\"5\"");
        assertThat(RawHttp.get("/wp-json/qp-test/v1/../v1/echo/5").text()).contains("rest_no_route");
    }

    @Test
    void routesLongerThan2048BytesMatchNothing() {
        // "/qp-test/v1/echo/" is 17 bytes
        String longest = "7".repeat(2048 - 17);
        Http.as(null)
                .get("/?rest_route=/qp-test/v1/echo/" + longest)
                .then()
                .statusCode(200)
                .body("urlId", equalTo(longest));
        Http.as(null)
                .get("/?rest_route=/qp-test/v1/echo/" + longest + "7")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
        Http.as(null)
                .contentType("application/x-www-form-urlencoded")
                .body("rest_route=/qp-test/v1/echo/" + "7".repeat(1_000_000))
                .post("/")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
    }
}
