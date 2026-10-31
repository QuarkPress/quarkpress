package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * On a network, embedding stays on the site the request is for: a link into another site's path on the same host, a
 * dot or percent-encoded segment, never embeds another site's data.
 */
class LinksEmbedMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.multisite(LinksEmbedMultisiteDbTest.class, TestRoutes.class);

    String news;
    SiteId newsId;

    @BeforeEach
    void subdirectorySite() {
        AgroalDataSource data = Jdbc.dataSource();
        news = "news" + UUID.randomUUID().toString().substring(0, 8);
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        newsId = SiteId.of(Jdbc.count(data, "select id from qp_site where path = ?", "/" + news + "/"));
    }

    @Test
    void theMainSiteNeverEmbedsAnotherSitesData() {
        String other = "http://localhost/" + news + "/";
        JsonNode answer = json(Http.as(null)
                .queryParam("api", other + "wp-json/qp-test/v1/site")
                .queryParam("route", other + "?rest_route=/qp-test/v1/site")
                .queryParam("dots", "http://localhost/wp-json/../" + news + "/wp-json/qp-test/v1/site")
                .queryParam("encoded", "http://localhost/wp-json/%2e%2e/" + news + "/wp-json/qp-test/v1/site")
                .queryParam("escaped", "http://localhost/wp-json/qp-test/v1/%73ite")
                .queryParam("own", "http://localhost/wp-json/qp-test/v1/site")
                .get("/wp-json/qp-test/v1/linker?_embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        JsonNode embedded = answer.get("_embedded");
        // another site's API root is not followed at all
        assertThat(embedded.has("api")).isFalse();
        // a rest_route below this site's home is followed on this site (as WordPress does): the main site answers
        assertThat(embedded.at("/route/0").toString()).isEqualTo("{\"site\":1}");
        // dot and percent-encoded segments are taken literally: no route, never the other site
        for (String rel : new String[] {"dots", "encoded", "escaped"}) {
            assertThat(embedded.at("/" + rel + "/0/code").toString()).as(rel).isEqualTo("\"rest_no_route\"");
            assertThat(embedded.at("/" + rel + "/0/data/status").toString())
                    .as(rel)
                    .isEqualTo("404");
        }
        assertThat(embedded.at("/own/0").toString()).isEqualTo("{\"site\":1}");
    }

    @Test
    void aSubdirectorySiteEmbedsOnlyItsOwnData() {
        String own = "http://localhost/" + news + "/";
        JsonNode answer = json(Http.as(null)
                .queryParam("main", "http://localhost/wp-json/qp-test/v1/site")
                .queryParam("mainRoute", "http://localhost/?rest_route=/qp-test/v1/site")
                .queryParam("sibling", "http://localhost/" + news + "-other/wp-json/qp-test/v1/site")
                .queryParam("own", own + "wp-json/qp-test/v1/site")
                .queryParam("ownRoute", own + "?rest_route=/qp-test/v1/site")
                .get("/" + news + "/wp-json/qp-test/v1/linker?_embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        JsonNode embedded = answer.get("_embedded");
        assertThat(fieldNames(embedded)).containsExactly("own", "ownRoute");
        assertThat(embedded.at("/own/0").toString()).isEqualTo("{\"site\":" + newsId.value() + "}");
        assertThat(embedded.at("/ownRoute/0").toString()).isEqualTo("{\"site\":" + newsId.value() + "}");
        // the links themselves are listed, on the subsite's own root
        assertThat(answer.at("/_links/own/0/href").toString()).isEqualTo("\"" + own + "wp-json/qp-test/v1/site\"");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
