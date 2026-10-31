package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.http.Header;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.SiteScope;

/** On a network, each site answers for itself, and a site that may not be served answers before any route. */
class MultisiteDispatchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.multisite(MultisiteDispatchDbTest.class, TestRoutes.class);

    static final String NO_ROUTE = DispatchDbTest.NO_ROUTE;

    static final String ARCHIVED = "This site has been archived or suspended.";
    static final String GONE = "This site is no longer available.";

    @Inject
    SiteScope scope;

    @Inject
    FeatureToggles toggles;

    String news;
    SiteId newsId;
    UserId admin;

    @BeforeEach
    void subdirectorySite() {
        AgroalDataSource data = Jdbc.dataSource();
        news = "news" + UUID.randomUUID().toString().substring(0, 8);
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        newsId = SiteId.of(Jdbc.count(data, "select id from qp_site where path = ?", "/" + news + "/"));
        admin = TestData.profile(data, "administrator");
        TestData.member(data, newsId, admin, "[\"administrator\"]");
    }

    @Test
    void aSubdirectorySiteAnswersForItselfAndOnlyThroughItsOwnPath() {
        Http.as(null).get("/wp-json/qp-test/v1/site").then().statusCode(200).body("site", equalTo(1));
        for (String url :
                List.of("/" + news + "/wp-json/qp-test/v1/site", "/" + news + "/?rest_route=/qp-test/v1/site")) {
            Http.as(null).get(url).then().statusCode(200).body("site", equalTo((int) newsId.value()));
        }
        // the main site, whose path does not hold the base right after it: passed on, not a REST answer
        Http.as(null)
                .get("/" + news + "-typo/wp-json/qp-test/v1/site")
                .then()
                .statusCode(404)
                .header("Link", nullValue())
                .body(not(containsString("rest_no_route")));
        scope.run(newsId, () -> toggles.setForSite("rest-wp-v2", false));
        Http.as(null)
                .get("/" + news + "/wp-json/qp-test/v1/site")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as(null)
                .get("/" + news + "/?rest_route=/qp-test/v1/site")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
        Http.as(null).get("/wp-json/qp-test/v1/site").then().statusCode(200).body("site", equalTo(1));
    }

    @Test
    void anUnresolvedHostIsNeverServed() {
        String host = "unknown-" + UUID.randomUUID().toString().substring(0, 8) + ".test";
        for (String bearer : new String[] {null, "invalid", "test-" + admin.value()}) {
            for (String url : List.of("/wp-json/qp-test/v1/site", "/?rest_route=/qp-test/v1/echo/5", "/wp-json/")) {
                ExtractableResponse<Response> response = Http.at(host, bearer)
                        .get(url)
                        .then()
                        .statusCode(404)
                        .body(equalTo(NO_ROUTE))
                        .extract();
                for (Header header : response.headers()) {
                    assertThat(header.getValue()).as(header.getName()).doesNotContain(host);
                }
            }
        }
        Http.at(host, null)
                .contentType("application/json")
                .body("{nope")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(404)
                .body(equalTo(NO_ROUTE));
    }

    @Test
    void aRedirectingAliasSends301ForReadsOnly() {
        String alias = "alias-" + UUID.randomUUID().toString().substring(0, 8) + ".test";
        Jdbc.update(
                Jdbc.dataSource(),
                "insert into qp_site_domain (site_id, domain, redirect_to_primary) values (?, ?, ?)",
                newsId.value(),
                alias,
                true);
        String location = "http://localhost/" + news + "/wp-json/qp-test/v1/items?x=1";
        for (String method : List.of("GET", "HEAD")) {
            Http.at(alias, null)
                    .request(method, "/wp-json/qp-test/v1/items?x=1")
                    .then()
                    .statusCode(301)
                    .header("Location", location);
        }
        Http.at(alias, null)
                .get("/?rest_route=/qp-test/v1/items")
                .then()
                .statusCode(301)
                // the query as the client sent it (REST Assured percent-encodes the slashes)
                .header("Location", "http://localhost/" + news + "/?rest_route=%2Fqp-test%2Fv1%2Fitems")
                .header("Content-Type", nullValue());
        // the decision follows the overridden method
        Http.at(alias, null)
                .header("X-HTTP-Method-Override", "POST")
                .get("/wp-json/qp-test/v1/json?a=2")
                .then()
                .statusCode(200)
                .body(equalTo("{\"a\":\"2\"}"));
        Http.at(alias, null)
                .header("X-HTTP-Method-Override", "GET")
                .post("/wp-json/qp-test/v1/items?x=1")
                .then()
                .statusCode(301)
                .header("Location", location);
        Http.at(alias, null)
                .contentType("application/json")
                .body("{\"a\":1}")
                .post("/wp-json/qp-test/v1/json")
                .then()
                .statusCode(200)
                .body(equalTo("{\"a\":1}"));
    }

    @Test
    void archivedSpamAndDeletedSitesAnswer410() {
        for (String flag : List.of("archived", "spam", "deleted")) {
            String message = flag.equals("deleted") ? GONE : ARCHIVED;
            Jdbc.update(Jdbc.dataSource(), "update qp_site set " + flag + " = ? where id = ?", true, newsId.value());
            try {
                for (String bearer : new String[] {null, "test-" + admin.value(), "invalid"}) {
                    noRestHeaders(Http.as(bearer).get("/" + news + "/wp-json/").then())
                            .statusCode(410)
                            .header("Content-Type", "text/html; charset=UTF-8")
                            .body(containsString("<div class=\"wp-die-message\">" + message + "</div>"));
                    String json = "{\"code\":\"wp_die\",\"message\":\"" + message
                            + "\",\"data\":{\"status\":410},\"additional_errors\":[]}";
                    noRestHeaders(Http.as(bearer)
                                    .header("Accept", "application/json, */*;q=0.1")
                                    .get("/" + news + "/wp-json/")
                                    .then())
                            .statusCode(410)
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .body(equalTo(json));
                    noRestHeaders(Http.as(bearer)
                                    .contentType("application/json")
                                    .body("{\"a\":1}")
                                    .post("/" + news + "/wp-json/qp-test/v1/json")
                                    .then())
                            .statusCode(410)
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .body(equalTo(json));
                }
            } finally {
                Jdbc.update(
                        Jdbc.dataSource(), "update qp_site set " + flag + " = ? where id = ?", false, newsId.value());
            }
        }
        Http.as(null).get("/" + news + "/wp-json/qp-test/v1/site").then().statusCode(200);
    }

    private static ValidatableResponse noRestHeaders(ValidatableResponse response) {
        return response.header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private")
                .header("Link", nullValue())
                .header("X-Robots-Tag", nullValue())
                .header("X-Content-Type-Options", nullValue())
                .header("Allow", nullValue())
                .header("WWW-Authenticate", nullValue())
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Expose-Headers", nullValue())
                .header("Access-Control-Allow-Headers", nullValue());
    }
}
