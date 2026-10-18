package org.quarkpress.it.multisite;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MultisiteDbTest {

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long createSite(String domain, String path, Map<String, Boolean> features) {
        return given().header("Host", "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", domain, "path", path, "title", domain + path, "features", features))
                .post("/it/network/sites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private static long siteSeenAt(String host, String path) {
        return given().header("Host", host)
                .get(path)
                .then()
                .statusCode(200)
                .body("resolved", is(true))
                .extract()
                .jsonPath()
                .getLong("id");
    }

    @Test
    void theMainSiteAnswersOnTheNetworkDomain() {
        assertThat(siteSeenAt("localhost", "/")).isEqualTo(1);
    }

    @Test
    void aPathSiteAnswersUnderItsPath() {
        String slug = unique("path");
        long site = createSite("localhost", "/" + slug + "/", Map.of());
        assertThat(siteSeenAt("localhost", "/" + slug + "/a/b")).isEqualTo(site);
        assertThat(siteSeenAt("localhost", "/" + slug)).isEqualTo(site);
        assertThat(siteSeenAt("localhost", "/wp-json/wp/v2/posts")).isEqualTo(1);
    }

    @Test
    void aSubdomainSiteAnswers() {
        String host = unique("sub") + ".localhost";
        long site = createSite(host, "/", Map.of());
        assertThat(siteSeenAt(host, "/")).isEqualTo(site);
    }

    @Test
    void aMappedDomainAnswersWithAndWithoutWww() {
        String domain = unique("mapped") + ".example";
        long site = createSite(domain, "/", Map.of());
        assertThat(siteSeenAt(domain + ":8080", "/x")).isEqualTo(site);
        assertThat(siteSeenAt("www." + domain, "/x")).isEqualTo(site);
    }

    @Test
    void aRedirectingAliasExposesThePrimaryUrl() {
        String domain = unique("primary") + ".example";
        long site = createSite(domain, "/", Map.of());
        String alias = unique("alias") + ".example";
        given().header("Host", "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", alias, "redirect", true))
                .post("/it/network/sites/" + site + "/domains")
                .then()
                .statusCode(200)
                .body("domain", is(alias));
        given().header("Host", alias)
                .get("/x")
                .then()
                .statusCode(200)
                .body("id", is((int) site))
                .body("redirect", is("http://" + domain + "/"));
    }

    @Test
    void anUnknownHostIsServedWithoutASite() {
        given().header("Host", unique("nobody") + ".example")
                .get("/x")
                .then()
                .statusCode(200)
                .body("resolved", is(false))
                .body("id", nullValue());
    }

    @Test
    void dataCallsUnderAnUnknownHostSeeNoSiteData() {
        String host = unique("nobody") + ".example";
        // Notes of the main site exist, so a fallback to it would show.
        long note = given().header("Host", "localhost")
                .post("/it/data/notes/" + unique("main-note"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        given().header("Host", "localhost").get("/it/data/notes/" + note).then().statusCode(200);
        given().header("Host", host)
                .get("/it/data/notes")
                .then()
                .statusCode(200)
                .body("$", empty());
        given().header("Host", host).get("/it/data/notes/" + note).then().statusCode(404);
        // the library itself refuses the read: the resource does not check for a site first
        given().header("Host", host)
                .get("/it/data/options/blogname")
                .then()
                .statusCode(404)
                .body(is("no site"));
    }

    @Test
    void optionsAreIsolatedBetweenSites() {
        String a = unique("opt-a") + ".example";
        String b = unique("opt-b") + ".example";
        createSite(a, "/", Map.of());
        createSite(b, "/", Map.of());
        String name = unique("option");
        given().header("Host", a)
                .contentType(ContentType.JSON)
                .body("{\"k\":[1,2]}")
                .put("/it/data/options/" + name)
                .then()
                .statusCode(200)
                .body("changed", is(true));
        given().header("Host", a)
                .get("/it/data/options/" + name)
                .then()
                .statusCode(200)
                .body("k", contains(1, 2));
        given().header("Host", b).get("/it/data/options/" + name).then().statusCode(404);
        given().header("Host", b)
                .contentType(ContentType.JSON)
                .body("{\"k\":[9]}")
                .put("/it/data/options/" + name)
                .then()
                .statusCode(200)
                .body("changed", is(true));
        given().header("Host", a)
                .get("/it/data/options/" + name)
                .then()
                .statusCode(200)
                .body("k", contains(1, 2));
        given().header("Host", b)
                .get("/it/data/options/" + name)
                .then()
                .statusCode(200)
                .body("k", contains(9));
    }

    @Test
    void rowsAreIsolatedAndCrossSiteReferencesRefused() {
        String a = unique("rows-a") + ".example";
        String b = unique("rows-b") + ".example";
        createSite(a, "/", Map.of());
        createSite(b, "/", Map.of());
        String text = unique("note");
        long note = given().header("Host", a)
                .post("/it/data/notes/" + text)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        given().header("Host", a).get("/it/data/notes").then().statusCode(200).body("$", contains(text));
        given().header("Host", b).get("/it/data/notes").then().statusCode(200).body("$", empty());
        given().header("Host", a)
                .get("/it/data/notes/" + note)
                .then()
                .statusCode(200)
                .body("text", is(text));
        given().header("Host", b).get("/it/data/notes/" + note).then().statusCode(404);
        given().header("Host", b)
                .post("/it/data/notes/" + note + "/tags/stolen")
                .then()
                .statusCode(200)
                .body(is("refused"));
        given().header("Host", a)
                .post("/it/data/notes/" + note + "/tags/own")
                .then()
                .statusCode(200)
                .body(is("stored"));
    }

    @Test
    void featuresArePerSite() {
        String on = unique("demo-on") + ".example";
        String off = unique("demo-off") + ".example";
        createSite(on, "/", Map.of("demo", true));
        createSite(off, "/", Map.of("demo", false));
        given().header("Host", on).get("/it/demo").then().statusCode(200).body(is("demo"));
        given().header("Host", off).get("/it/demo").then().statusCode(404);
    }

    private static List<Long> created(String path) {
        return given().header("Host", "localhost")
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$", Long.class);
    }

    @Test
    void anAsyncObserverFollowsTheCreatedSite() throws InterruptedException {
        long on = createSite(unique("async-on") + ".example", "/", Map.of("demo", true));
        long off = createSite(unique("async-off") + ".example", "/", Map.of("demo", false));
        // The ungated observer has seen both sites once all events were delivered, so the gated list is final.
        List<Long> all = List.of();
        for (int attempt = 0; attempt < 100 && !(all.contains(on) && all.contains(off)); attempt++) {
            all = created("/it/network/created/all");
            if (!(all.contains(on) && all.contains(off))) {
                Thread.sleep(100);
            }
        }
        assertThat(all).contains(on, off);
        assertThat(created("/it/network/created")).contains(on).doesNotContain(off);
        // the sites were created from a request on the main site; their asynchronous observers see no site
        Map<String, String> contexts = given().header("Host", "localhost")
                .get("/it/network/created/contexts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("$", String.class, String.class);
        assertThat(contexts)
                .containsEntry(String.valueOf(on), "unresolved")
                .containsEntry(String.valueOf(off), "unresolved");
    }

    @Test
    void deletingASiteRemovesItsRows() {
        String domain = unique("delete") + ".example";
        long site = createSite(domain, "/", Map.of());
        given().header("Host", "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", unique("gone-alias") + ".example", "redirect", false))
                .post("/it/network/sites/" + site + "/domains")
                .then()
                .statusCode(200);
        long note = given().header("Host", domain)
                .post("/it/data/notes/" + unique("doomed"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        given().header("Host", domain)
                .post("/it/data/notes/" + note + "/tags/t")
                .then()
                .body(is("stored"));
        given().header("Host", "localhost")
                .get("/it/network/sites/" + site + "/rows")
                .then()
                .statusCode(200)
                .body("qp_option", greaterThan(0))
                .body("qp_site_domain", greaterThan(0))
                .body("it_note", is(1))
                .body("it_note_tag", is(1));
        given().header("Host", "localhost")
                .delete("/it/network/sites/" + site)
                .then()
                .statusCode(204);
        given().header("Host", domain).get("/").then().statusCode(200).body("resolved", is(false));
        given().header("Host", "localhost")
                .get("/it/network/sites/" + site + "/rows")
                .then()
                .statusCode(200)
                .body("qp_option", is(0))
                .body("qp_site_domain", is(0))
                .body("it_note", is(0))
                .body("it_note_tag", is(0));
    }

    @Test
    void anArchivedSiteStillResolvesWithItsStatus() {
        String domain = unique("archived") + ".example";
        long site = createSite(domain, "/", Map.of());
        given().header("Host", "localhost")
                .put("/it/network/sites/" + site + "/flags/ARCHIVED/true")
                .then()
                .statusCode(200)
                .body("status", is("ARCHIVED"));
        given().header("Host", domain).get("/").then().statusCode(200).body("status", is("ARCHIVED"));
    }
}
