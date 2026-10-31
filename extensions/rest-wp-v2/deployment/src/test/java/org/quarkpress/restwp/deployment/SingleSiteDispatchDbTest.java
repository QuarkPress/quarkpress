package org.quarkpress.restwp.deployment;

import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The request path of a single site (the multisite module turned off): the API root, a route by path and by
 * {@code rest_route}, the namespace index, an unknown route and the fixed headers, all through the single-site
 * resolution.
 */
class SingleSiteDispatchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.singleSite(SingleSiteDispatchDbTest.class);

    @Test
    void routesAreServedOnTheOnlySite() {
        Http.as(null)
                .get("/wp-json/")
                .then()
                .statusCode(200)
                .header("Link", "<http://localhost/wp-json/>; rel=\"https://api.w.org/\"")
                .header("X-Content-Type-Options", "nosniff")
                .body("url", equalTo("http://localhost"))
                .body("namespaces[0]", equalTo("wp/v2"));
        Http.as(null).get("/wp-json/wp/v2/types/post").then().statusCode(200).body("slug", equalTo("post"));
        Http.as(null)
                .get("/?rest_route=/wp/v2/types/page")
                .then()
                .statusCode(200)
                .body("slug", equalTo("page"));
        Http.as(null).get("/wp-json/wp/v2").then().statusCode(200).body("namespace", equalTo("wp/v2"));
        Http.as(null)
                .get("/wp-json/wp/v2/nothing-here")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"))
                .body("message", equalTo("No route was found matching the URL and request method."));
        Http.as(null)
                .get("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .body("code", equalTo("rest_not_logged_in"));
    }
}
