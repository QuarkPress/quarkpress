package org.quarkword.it.mediavips;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wprestparity.MediaFixtures;
import org.quarkword.testing.wprestparity.MediaFixtures.JpegMeta;

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
                4000, 3000, JpegMeta.none().withOrientation(6).withExif("Model", "QuarkWord One"));
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
        assertThat(item.getString("media_details.image_meta.camera")).isEqualTo("QuarkWord One");
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

    /** An 8&times;8 black JPEG XL, a bare codestream: {@code vips jxlsave black.v black.jxl --lossless --keep none}. */
    static final byte[] JPEG_XL = HexFormat.of().parseHex("ff0a414024080401001c004b188b15820300");

    @Test
    void aJpegXlOrAPdfNamedJpegIsRefusedAsATypeMismatch() {
        // the content is not the name's type: refused as WordPress refuses it (finfo's answer differs from the
        // extension's type), before anything is stored and before libvips opens it
        Map<String, byte[]> impostors = new LinkedHashMap<>();
        impostors.put("rest-impostor-jxl.jpg", JPEG_XL);
        impostors.put("rest-impostor-pdf.jpg", MediaFixtures.pdf());
        impostors.forEach((name, bytes) -> {
            Response refused =
                    as("bob").multiPart("file", name, bytes, "image/jpeg").post(MEDIA);
            assertThat(refused.statusCode())
                    .as(name + ": " + refused.asString())
                    .isEqualTo(500);
            JsonPath error = refused.jsonPath();
            assertThat(error.getString("code")).as(name).isEqualTo("rest_upload_unknown_error");
            assertThat(error.getString("message"))
                    .as(name)
                    .isEqualTo("Sorry, you are not allowed to upload this file type.");
            assertThat(error.getInt("data.status")).as(name).isEqualTo(500);
        });
        // no attachment names them (the search also matches the attached file's path) ...
        List<Object> found = as("bob")
                .queryParam("search", "rest-impostor")
                .queryParam("context", "edit")
                .get(MEDIA)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        assertThat(found).isEmpty();
        // ... and no file of theirs is in this month's folder, where a probe upload lands
        long probe = uploaded("rest-impostor-probe.png", "image/png", MediaFixtures.png(64, 48), "")
                .getLong("id");
        assertThat(folderFiles(probe))
                .noneMatch(file -> file.startsWith("rest-impostor-jxl") || file.startsWith("rest-impostor-pdf"));
        assertNoImageOpen();
    }

    @Test
    void twoPostProcessesOfOneAttachmentAtOnceAgree() throws Exception {
        // the stem of each size of a 1600x1200 image, with its dimensions
        List<String> sizes = List.of("150x150", "300x225", "768x576", "1024x768", "1536x1152");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                String stem = "rest-race-" + round;
                long id = uploaded(
                                stem + ".png", "image/png", MediaFixtures.png(1600, 1200), "?generate_sub_sizes=false")
                        .getLong("id");
                String token = keycloak.getAccessToken("bob");
                CountDownLatch start = new CountDownLatch(1);
                Callable<Response> postProcess = () -> {
                    start.await();
                    return given().header("Host", "localhost")
                            .auth()
                            .oauth2(token)
                            .contentType(ContentType.JSON)
                            .body(Map.of("action", "create-image-subsizes"))
                            .post(MEDIA + "/" + id + "/post-process");
                };
                Future<Response> first = pool.submit(postProcess);
                Future<Response> second = pool.submit(postProcess);
                start.countDown();
                for (Future<Response> run : List.of(first, second)) {
                    Response answer = run.get(60, TimeUnit.SECONDS);
                    assertThat(answer.statusCode())
                            .as(stem + ": " + answer.asString())
                            .isEqualTo(200);
                    assertThat(answer.jsonPath().getList("missing_image_sizes"))
                            .as(stem)
                            .isEmpty();
                }
                // the metadata names each size once, by its own file, and the folder holds those files only
                JsonPath item = as("bob")
                        .queryParam("context", "edit")
                        .get(MEDIA + "/" + id)
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath();
                Map<String, String> expected = new LinkedHashMap<>();
                List<String> names = List.of("thumbnail", "medium", "medium_large", "large", "1536x1536");
                for (int i = 0; i < names.size(); i++) {
                    expected.put(names.get(i), stem + "-" + sizes.get(i) + ".png");
                }
                assertThat(files(item)).as(stem).containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(folderFiles(id).stream().filter(file -> file.startsWith(stem)))
                        .as(stem)
                        .containsExactlyInAnyOrderElementsOf(concat(stem + ".png", expected.values()));
                JsonPath wordPress = as("bob")
                        .get("/it/media/" + id + "/wordpress")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath();
                Map<String, Map<String, Object>> stored = wordPress.getMap("meta._wp_attachment_metadata.sizes");
                assertThat(stored).as(stem).hasSize(expected.size());
                for (String name : names) {
                    String dimensions = sizes.get(names.indexOf(name));
                    Response served = fetch(item.getString("media_details.sizes." + name + ".source_url"));
                    assertThat(served.statusCode()).as(stem + " " + name).isEqualTo(200);
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(served.asByteArray()));
                    assertThat(image).as(stem + " " + name).isNotNull();
                    assertThat(image.getWidth() + "x" + image.getHeight())
                            .as(stem + " " + name)
                            .isEqualTo(dimensions);
                    assertThat(((Number) stored.get(name).get("filesize")).longValue())
                            .as(stem + " " + name)
                            .isEqualTo(served.asByteArray().length);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertNoImageOpen();
    }

    /** The sorted file names in the folder of attachment {@code id}'s main file. */
    private List<String> folderFiles(long id) {
        return as("bob")
                .get("/it/media/" + id + "/wordpress")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("files", String.class);
    }

    private static List<String> concat(String first, Collection<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private void assertNoImageOpen() {
        JsonPath images = as(null).get("/it/vips/open-images")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(images.getLong("open")).isZero();
        assertThat(images.getLong("leaked")).isZero();
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
