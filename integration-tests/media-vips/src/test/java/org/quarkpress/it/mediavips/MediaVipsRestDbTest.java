package org.quarkpress.it.mediavips;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * The libvips image processor behind WordPress's REST API: uploads ({@code POST /wp-json/wp/v2/media}) and the
 * post-process run on worker threads, where libvips may block, and the readiness check reports libvips.
 */
@QuarkusTest
@WithTestResource(MediaFilesResource.class)
class MediaVipsRestDbTest {

    // The application (and its storage directory) is shared with MediaVipsDbTest: file names here start "rest-".

    private static final String MEDIA = "/wp-json/wp/v2/media";

    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        long bob = as("bob")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", bob, "role", "editor"))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    /** Uploads {@code bytes} as bob in a multipart form; expects 201 and answers the item. */
    private JsonPath uploaded(String name, String type, byte[] bytes, String query) {
        Response answer = as("bob").multiPart("file", name, bytes, type).post(MEDIA + query);
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(201);
        return answer.jsonPath();
    }

    private JsonPath formats() {
        return as(null).get("/it/vips/formats").then().statusCode(200).extract().jsonPath();
    }

    private Response fetch(String url) {
        return as(null).get(URI.create(url).getRawPath());
    }

    /** Each generated size's file by size name, from {@code media_details.sizes} without {@code full}. */
    private static Map<String, String> files(JsonPath item) {
        Map<String, Map<String, Object>> sizes = item.getMap("media_details.sizes");
        Map<String, String> files = new LinkedHashMap<>();
        sizes.forEach((name, size) -> files.put(name, (String) size.get("file")));
        // the REST API adds the main file as "full"
        files.remove("full");
        return files;
    }

    /** The {@code mime_type} of every size. */
    private static List<Object> sizeTypes(JsonPath item) {
        Map<String, Map<String, Object>> sizes = item.getMap("media_details.sizes");
        return sizes.entrySet().stream()
                .filter(size -> !size.getKey().equals("full"))
                .map(size -> size.getValue().get("mime_type"))
                .distinct()
                .toList();
    }

    @Test
    void aJpegUploadedThroughRestGetsItsSizes() {
        byte[] photo = MediaFixtures.jpeg(
                4000, 3000, JpegMeta.none().withOrientation(6).withExif("Model", "QuarkPress One"));
        JsonPath item = uploaded("rest-photo.jpg", "image/jpeg", photo, "");
        assertThat(item.getString("mime_type")).isEqualTo("image/jpeg");
        assertThat(item.getString("media_details.file")).endsWith("/rest-photo-scaled.jpg");
        assertThat(item.getString("media_details.original_image")).isEqualTo("rest-photo.jpg");
        assertThat(item.getInt("media_details.width")).isEqualTo(1920);
        assertThat(item.getInt("media_details.height")).isEqualTo(2560);
        // the media app's table for this image, which the media parity run checks against WordPress
        Map<String, String> files = files(item);
        assertThat(files)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "rest-photo-150x150.jpg",
                        "medium", "rest-photo-225x300.jpg",
                        "medium_large", "rest-photo-768x1024.jpg",
                        "large", "rest-photo-768x1024.jpg",
                        "1536x1536", "rest-photo-1152x1536.jpg",
                        "2048x2048", "rest-photo-1536x2048.jpg"));
        assertThat(sizeTypes(item)).containsExactly("image/jpeg");
        // the camera's EXIF was read, and no size file carries it
        assertThat(item.getString("media_details.image_meta.camera")).isEqualTo("QuarkPress One");
        for (String size : files.keySet()) {
            Response served = fetch(item.getString("media_details.sizes." + size + ".source_url"));
            assertThat(served.statusCode()).as(size).isEqualTo(200);
            assertThat(hasExif(served.asByteArray())).as(size).isFalse();
        }
    }

    @Test
    void webpAvifAndGifUploadsAreAcceptedWithTheirOwnSizes() throws IOException {
        List<String> writable = formats().getList("writable", String.class);
        boolean all = Boolean.getBoolean("vips.require-all-formats");
        boolean any = false;
        if (writable.contains("image/webp") || all) {
            JsonPath webp = uploaded("rest-wide.webp", "image/webp", MediaFixtures.webpLossy(3000, 2000), "");
            assertThat(webp.getString("media_details.file")).endsWith("/rest-wide-scaled.webp");
            assertThat(files(webp)).hasSize(6);
            assertThat(sizeTypes(webp)).containsExactly("image/webp");
            any = true;
        }
        if (writable.contains("image/avif") || all) {
            JsonPath avif = uploaded("rest-sky.avif", "image/avif", MediaFixtures.avif(2600, 1800), "");
            assertThat(avif.getString("media_details.file")).endsWith("/rest-sky-scaled.avif");
            assertThat(sizeTypes(avif)).containsExactly("image/avif");
            any = true;
        }
        if (writable.contains("image/gif") || all) {
            JsonPath gif = uploaded("rest-moving.gif", "image/gif", MediaFixtures.gif(800, 600, 3), "");
            assertThat(files(gif))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "thumbnail", "rest-moving-150x150.gif",
                            "medium", "rest-moving-300x225.gif",
                            "medium_large", "rest-moving-768x576.gif"));
            for (String size : files(gif).keySet()) {
                byte[] bytes = fetch(gif.getString("media_details.sizes." + size + ".source_url"))
                        .asByteArray();
                assertThat(MediaVipsDbTest.frames(bytes)).as(size).isEqualTo(1);
            }
            any = true;
        }
        assumeTrue(any, "SKIPPED (libvips writes neither WebP, AVIF nor GIF)");
    }

    @Test
    void aHeicUploadBecomesJpeg() {
        assumeTrue(
                formats().getList("readable", String.class).contains("image/heic")
                        || Boolean.getBoolean("vips.require-all-formats"),
                MediaVipsDbTest.HEIC_SKIP);
        JsonPath item = uploaded("IMG_0001.heic", "image/heic", MediaFixtures.heic(4032, 3024), "");
        assertThat(item.getString("mime_type")).isEqualTo("image/heic");
        assertThat(item.getString("media_details.file")).endsWith("/IMG_0001-scaled.jpg");
        assertThat(item.getString("media_details.original_image")).isEqualTo("IMG_0001.heic");
        assertThat(item.getInt("media_details.width")).isEqualTo(2560);
        assertThat(item.getInt("media_details.height")).isEqualTo(1920);
        assertThat(files(item)).hasSize(6);
        assertThat(sizeTypes(item)).containsExactly("image/jpeg");
        assertThat(fetch(item.getString("source_url")).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void postProcessRunsOnAWorkerAndFillsMissingSizes() {
        JsonPath item =
                uploaded("rest-later.png", "image/png", MediaFixtures.png(800, 600), "?generate_sub_sizes=false");
        int id = item.getInt("id");
        assertThat(files(item)).isEmpty();
        JsonPath processed = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("action", "create-image-subsizes"))
                .post(MEDIA + "/" + id + "/post-process")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(processed.getInt("id")).isEqualTo(id);
        assertThat(files(processed))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "rest-later-150x150.png",
                        "medium", "rest-later-300x225.png",
                        "medium_large", "rest-later-768x576.png"));
        assertThat(processed.getList("missing_image_sizes")).isEmpty();
    }

    @Test
    void theReadinessCheckReportsLibvips() {
        JsonPath health =
                as(null).get("/q/health/ready").then().statusCode(200).extract().jsonPath();
        Map<String, Object> libvips = health.getMap("checks.find { it.name == 'libvips' }");
        assertThat(libvips).containsEntry("status", "UP");
        Map<String, Object> data = health.getMap("checks.find { it.name == 'libvips' }.data");
        assertThat(data).containsEntry("available", true);
        // no path of the host reaches a client
        assertThat(data.values())
                .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("/"));
    }

    /** Whether a JPEG has an APP1 segment of EXIF data before its image data. */
    static boolean hasExif(byte[] jpeg) {
        byte[] signature = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return false;
            }
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            if (marker == 0xE1 && length >= 2 + signature.length) {
                boolean exif = true;
                for (int i = 0; i < signature.length; i++) {
                    exif &= jpeg[at + 4 + i] == signature[i];
                }
                if (exif) {
                    return true;
                }
            }
            at += 2 + length;
        }
        return false;
    }
}
