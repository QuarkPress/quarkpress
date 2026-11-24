package org.quarkword.it.restwp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound fetcher end to end, against the local server of {@link LocalOutbound}: the oEmbed proxy fetches a
 * provider and filters its HTML, a URL-mode upload stores the fetched image, the byte cap aborts the transfer and a
 * redirect to a refused port is refused: in JVM mode here, against the packaged application in {@code OutboundIT},
 * and as a native executable in CI, where the Vert.x client, the address checks and the body stream run natively.
 */
@QuarkusTest
@TestProfile(LocalOutbound.class)
class OutboundDbTest {

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        role("bob", "editor");
        role("carol", "author");
    }

    private long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    /** Gives {@code user} the role on the main site, as alice. */
    private void role(String user, String role) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(user), "role", role))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    /** {@code POST /wp/v2/media} with a JSON body naming {@code url}, as carol. */
    private Response fromUrl(String url) {
        return as("carol")
                .contentType(ContentType.JSON)
                .body(Map.of("url", url))
                .post(MEDIA);
    }

    /** The path and query of an absolute link on the main site. */
    private static String local(String link) {
        assertThat(link).startsWith("http://localhost/");
        return link.substring("http://localhost".length());
    }

    @Test
    void anOEmbedProviderIsFetchedAndFiltered() {
        String url = LocalOutbound.url("/v/rich");
        Response response = as("bob").queryParam("url", url).get("/wp-json/oembed/1.0/proxy");
        response.then()
                .statusCode(200)
                .header("Allow", "GET")
                .body("type", equalTo("rich"))
                .body("provider_name", equalTo("Local"));
        String html = response.jsonPath().getString("html");
        // the script and the iframe's own attributes are gone; the iframe survives, sandboxed, titled and with a secret
        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("onload")
                .doesNotContain("allow=")
                .doesNotContain("\n")
                .startsWith("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " title=\"Local\" src=\"https://local.invalid/frame#?secret=")
                .contains("data-secret=\"");
        // the provider was asked as WordPress asks: the sizes, the URL, dnt and the format
        List<String> requests = LocalOutbound.log().stream()
                .filter(line -> line.startsWith("GET /oembed?"))
                .toList();
        assertThat(requests).isNotEmpty();
        String last = requests.get(requests.size() - 1);
        assertThat(URLDecoder.decode(last.substring(last.indexOf('?') + 1), StandardCharsets.UTF_8))
                .contains("maxwidth=600")
                .contains("maxheight=750")
                .contains("url=" + url)
                .contains("dnt=1")
                .contains("format=json");
    }

    @Test
    void aUrlUploadIsStored() {
        // a name of this run's own: the uploads folder keeps earlier runs' files, which would suffix a repeated name
        String name = "photo-" + Long.toHexString(System.nanoTime());
        Response answer = fromUrl(LocalOutbound.url("/" + name + ".jpg"));
        answer.then()
                .statusCode(201)
                .header("X-WP-Upload-Attachment-ID", nullValue())
                .body("title.raw", equalTo(name))
                .body("mime_type", equalTo("image/jpeg"))
                .body("media_details.width", equalTo(64))
                .body("media_details.height", equalTo(48));
        int id = answer.path("id");
        assertThat(answer.header("Location")).isEqualTo("http://localhost/wp-json/wp/v2/media/" + id);
        String source = answer.path("source_url");
        assertThat(source).startsWith("http://localhost/wp-content/uploads/").endsWith("/" + name + ".jpg");
        given().header("Host", "localhost")
                .get(local(source))
                .then()
                .statusCode(200)
                .contentType("image/jpeg");
        assertThat(LocalOutbound.log()).contains("GET /" + name + ".jpg 200");
    }

    @Test
    void theCapAndRedirectRulesHoldNatively() {
        // the 50 MiB body meets the application's own cap, the network's default per-file limit (fileupload_maxk,
        // 1500 KB): the upload refusal naming it, and the transfer aborted at the cap
        fromUrl(LocalOutbound.url("/huge.jpg"))
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_upload_file_too_big"))
                .body("message", equalTo("This file is too big. Files must be less than 1,500 KB in size."))
                .body("data.status", equalTo(400));
        long written = LocalOutbound.waitForReset("/huge.jpg");
        assertThat(written).as("bytes the server wrote before the reset").isLessThan(8L * 1024 * 1024);
        // a redirect to a refused port: WordPress's HTTP API error, and no connection to the port
        Response redirect = fromUrl(LocalOutbound.url("/redirect.jpg"));
        redirect.then().statusCode(500);
        assertThat(redirect.asString())
                .isEqualTo("{\"code\":\"http_request_failed\",\"message\":\"A valid URL was not provided.\","
                        + "\"data\":null}");
        assertThat(LocalOutbound.log()).contains("GET /redirect.jpg 302");
    }
}
