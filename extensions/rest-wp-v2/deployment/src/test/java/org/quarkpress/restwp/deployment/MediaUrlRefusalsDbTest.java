package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The refusals of an upload from a URL before anything is fetched, with the outbound module's address rule in force
 * (its default): a URL whose host is loopback, an IPv6 literal or unresolvable fails the argument's validation
 * (400 {@code rest_invalid_param}, detail {@code rest_invalid_url}, Part G fact v15) before the permission; a public
 * literal without a file name is the top-level {@code rest_invalid_url} of the file-name check; a name whose
 * extension is no image type the caller may upload is refused before any fetch. The fetch timeout is short, so a
 * fetch wrongly made would fail the test rather than hang it. The site's members: the author carol and the
 * contributor dave.
 */
class MediaUrlRefusalsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(MediaUrlRefusalsDbTest.class)
            .overrideConfigKey("quarkpress.rest-wp-v2.media-url-timeout", "2s");

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final String INVALID_URL =
            "Invalid URL. Provide a valid, publicly reachable HTTP or HTTPS image URL.";

    private static final ObjectMapper JSON = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static UserId carol;
    static UserId dave;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        carol = TestData.profile(data, "author");
        TestData.member(data, site, carol, "[\"author\"]");
        dave = TestData.profile(data, "contributor");
        TestData.member(data, site, dave, "[\"contributor\"]");
    }

    private static Response fromUrl(UserId user, String url) {
        return Http.at(host, "test-" + user.value())
                .contentType("application/json")
                .body("{\"url\":\"" + url + "\"}")
                .post(MEDIA);
    }

    private static JsonNode json(Response response) {
        try {
            return JSON.readTree(response.asString());
        } catch (IOException failure) {
            throw new AssertionError(response.asString(), failure);
        }
    }

    private static void invalidUrlParam(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.get("code").asText()).isEqualTo("rest_invalid_param");
        assertThat(body.get("message").asText()).isEqualTo("Invalid parameter(s): url");
        assertThat(body.at("/data/status").asInt()).isEqualTo(400);
        assertThat(body.at("/data/params").toString()).isEqualTo("{\"url\":\"" + INVALID_URL + "\"}");
        assertThat(body.at("/data/details").toString())
                .isEqualTo("{\"url\":{\"code\":\"rest_invalid_url\",\"message\":\"" + INVALID_URL
                        + "\",\"data\":{\"status\":400}}}");
    }

    private static void invalidUrl(Response response, String message) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(400);
        assertThat(response.asString())
                .isEqualTo("{\"code\":\"rest_invalid_url\",\"message\":\"" + message + "\",\"data\":{\"status\":400}}");
    }

    @Test
    void insideAddressesFailValidation() {
        for (String url : List.of(
                "http://127.0.0.1/a.jpg",
                "http://[::1]/a.jpg",
                "https://images.invalid/a.jpg",
                "http://10.1.2.3/a.jpg",
                "http://169.254.169.254/latest/a.jpg",
                "http://user@8.8.8.8/a.jpg",
                "http://8.8.8.8:22/a.jpg",
                "ftp://8.8.8.8/a.jpg",
                "notaurl",
                "")) {
            invalidUrlParam(fromUrl(carol, url));
        }
    }

    @Test
    void validationPrecedesThePermissionAndTheNameCheck() {
        // the argument is validated before the create permission
        invalidUrlParam(fromUrl(dave, "http://127.0.0.1/a.jpg"));
        Response forbidden = fromUrl(dave, "http://8.8.8.8/a.jpg");
        assertThat(forbidden.statusCode()).as(forbidden.asString()).isEqualTo(403);
        assertThat(json(forbidden).get("code").asText()).isEqualTo("rest_cannot_create");
        // an unresolvable host without a name is the validation's refusal
        invalidUrlParam(fromUrl(carol, "https://images.invalid/"));
        // a public literal without a name is the file-name check's, and nothing is fetched
        invalidUrl(fromUrl(carol, "http://8.8.8.8/"), "Could not determine a filename from the provided URL.");
        invalidUrl(fromUrl(carol, "http://8.8.8.8"), "Could not determine a filename from the provided URL.");
        invalidUrl(fromUrl(carol, "http://8.8.8.8//"), "Could not determine a filename from the provided URL.");
        // trailing slashes are dropped, as PHP's basename drops them: /dir/ names dir, which is no image
        invalidUrl(
                fromUrl(carol, "http://8.8.8.8/dir/?x=a.jpg"),
                "The provided URL does not point to a supported image file.");
    }

    @Test
    void nonImagesAreRefused() {
        for (String url : List.of(
                "http://8.8.8.8/a.pdf",
                "http://8.8.8.8/a.mp4",
                "http://8.8.8.8/a",
                "http://8.8.8.8/a.jpg.txt",
                "http://8.8.8.8/a.svg")) {
            invalidUrl(fromUrl(carol, url), "The provided URL does not point to a supported image file.");
        }
    }
}
