package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** A site whose {@code oembed} feature is off has no proxy route: every request to it answers as no route would. */
class OEmbedFeatureOffDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(OEmbedFeatureOffDbTest.class);

    private static final String NO_ROUTE = "{\"code\":\"rest_no_route\",\"message\":\"No route was found matching"
            + " the URL and request method.\",\"data\":{\"status\":404}}";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long bob;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, site, editor, "[\"editor\"]");
        bob = editor.value();
    }

    @Test
    void theFeatureOffAnswersNoRoute() {
        TestData.siteOption(data, site, "qp_features", "{\"oembed\": false}");
        Response off = Http.at(host, "test-" + bob)
                .queryParam("url", "https://media.invalid/a.mp3")
                .get("/wp-json/oembed/1.0/proxy");
        off.then().statusCode(404);
        assertThat(off.asString()).isEqualTo(NO_ROUTE);
        // the feature is on by default: the same request answers the audio handler's shortcode
        TestData.siteOption(data, site, "qp_features", "{\"oembed\": true}");
        Response on = Http.at(host, "test-" + bob)
                .queryParam("url", "https://media.invalid/a.mp3")
                .get("/wp-json/oembed/1.0/proxy");
        on.then().statusCode(200);
        assertThat(on.asString()).contains("Embed Handler");
    }
}
