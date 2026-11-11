package org.quarkpress.it.medias3;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The media library on S3 end to end, with real Keycloak tokens: uploads through the test resource ({@code /it/media})
 * stored by the S3 storage in moto (Dev Services), images processed by ImageIO, and every file fetched from moto
 * through the URL the storage gives it: presigned for an hour (the application's setting), or the bucket's own URL
 * ({@code /it/storage/bucket-url}). {@code /it/storage} also makes a conditional create on a taken key, a listing and
 * a delete. JVM mode here; {@code MediaS3IT} runs the same tests against the packaged application, and against the
 * native executable in CI, which is where the AWS SDK's calls and the presigner can fail.
 *
 * <p>Each start of the application stores its files under a new key prefix ({@link S3PrefixResource}).
 */
@QuarkusTest
@WithTestResource(S3PrefixResource.class)
class MediaS3DbTest {

    /** The presigned URLs' lifetime, from {@code quarkpress.media.s3.presigned-url-ttl=PT1H}. */
    private static final String PRESIGNED = "X-Amz-Expires=3600";

    /** WordPress's default sizes made from a 1600 by 1200 image, with their dimensions. */
    private static final Map<String, int[]> SIZES = Map.of(
            "thumbnail", new int[] {150, 150},
            "medium", new int[] {300, 225},
            "medium_large", new int[] {768, 576},
            "large", new int[] {1024, 768},
            "1536x1536", new int[] {1536, 1152});

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId("bob"), "role", "editor"))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    private long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    private static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Uploads a JPEG as {@code user} to the site at {@code sitePath} ({@code /} for the main site): the attachment. */
    private JsonPath uploaded(String user, String sitePath, String name, byte[] jpeg) {
        Response response = as(user).multiPart("file", name, jpeg, "image/jpeg").post(sitePath + "it/media");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        return response.jsonPath();
    }

    /** A GET of a file URL, sent to S3 (moto) without credentials. */
    private static HttpResponse<byte[]> fetch(String url) throws IOException, InterruptedException {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** The URL of every file of an attachment: the main file's, then each size's. */
    private static List<String> urls(JsonPath media) {
        List<String> urls = new ArrayList<>();
        urls.add(media.getString("url"));
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        sizes.values().forEach(size -> urls.add((String) size.get("url")));
        return urls;
    }

    @Test
    void anUploadIsStoredScaledAndServedFromS3() throws Exception {
        String name = "photo-" + token();
        JsonPath media = uploaded("bob", "/", name + ".jpg", MediaFixtures.jpeg(1600, 1200));
        String key = media.getString("storageKey");
        assertThat(key).matches("[0-9]{4}/[0-9]{2}/" + name + "\\.jpg");
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        assertThat(sizes).containsOnlyKeys(SIZES.keySet());

        // the main file and each size, each through its own presigned URL, decoding to the stated dimensions
        Map<String, int[]> files = new LinkedHashMap<>();
        files.put(media.getString("url"), new int[] {1600, 1200});
        sizes.forEach((size, entry) -> {
            assertThat(new int[] {(Integer) entry.get("width"), (Integer) entry.get("height")})
                    .as(size)
                    .containsExactly(SIZES.get(size));
            files.put((String) entry.get("url"), SIZES.get(size));
        });
        for (Map.Entry<String, int[]> file : files.entrySet()) {
            assertThat(file.getKey()).contains(PRESIGNED).contains("X-Amz-Signature=");
            HttpResponse<byte[]> served = fetch(file.getKey());
            assertThat(served.statusCode()).as(file.getKey()).isEqualTo(200);
            assertThat(served.headers().firstValue("Content-Type"))
                    .as(file.getKey())
                    .hasValue("image/jpeg");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(served.body()));
            assertThat(new int[] {image.getWidth(), image.getHeight()})
                    .as(file.getKey())
                    .containsExactly(file.getValue());
        }

        // the folder holds the original and the sizes
        String folder = key.substring(0, key.lastIndexOf('/'));
        List<String> listed = as(null).queryParam("folder", folder)
                .get("/it/storage/list")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("", String.class);
        List<String> expected = new ArrayList<>();
        expected.add(name + ".jpg");
        sizes.values().forEach(size -> expected.add((String) size.get("file")));
        assertThat(listed).containsAll(expected);
    }

    @Test
    void aSubdirectorySiteStoresUnderItsPrefix() throws Exception {
        String path = "/s-" + token() + "/";
        long site = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", "Media " + token()))
                .post("/it/sites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        JsonPath media = uploaded("alice", path, "kite.jpg", MediaFixtures.jpeg(400, 300));
        assertThat(media.getString("storageKey")).matches("sites/" + site + "/[0-9]{4}/[0-9]{2}/kite\\.jpg");
        assertThat(media.getString("sizes.thumbnail.key")).startsWith("sites/" + site + "/");
        for (String url : urls(media)) {
            assertThat(url).contains(PRESIGNED);
            assertThat(fetch(url).statusCode()).as(url).isEqualTo(200);
        }
        // the main site's library does not hold it
        as("alice").get("/it/media/" + media.getLong("id")).then().statusCode(404);
        as("alice").get(path + "it/media/" + media.getLong("id")).then().statusCode(200);
    }

    @Test
    void aTakenKeyIsRefusedAndTheBucketUrlServes() throws Exception {
        String key = token() + "/taken.bin";
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        as(null).queryParam("key", key)
                .contentType("application/octet-stream")
                .body(first)
                .post("/it/storage/create")
                .then()
                .statusCode(201);
        as(null).queryParam("key", key)
                .contentType("application/octet-stream")
                .body("second".getBytes(StandardCharsets.UTF_8))
                .post("/it/storage/create")
                .then()
                .statusCode(409);

        String url = as(null).queryParam("key", key)
                .get("/it/storage/bucket-url")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("url");
        assertThat(url)
                .doesNotContain("X-Amz-")
                .contains("/quarkpress-media/it-")
                .endsWith("/" + key);
        // the bucket URL serves a file only when it can be read without credentials
        assertThat(fetch(url).statusCode()).isEqualTo(403);
        as(null).queryParam("key", key).post("/it/storage/public-read").then().statusCode(204);
        HttpResponse<byte[]> served = fetch(url);
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).isEqualTo(first);

        // a delete answers whether the file was there
        assertThat(deleted(key)).isTrue();
        assertThat(deleted(key)).isFalse();
    }

    /** Deletes the key's file through the storage: whether it was there. */
    private boolean deleted(String key) {
        return as(null).queryParam("key", key)
                .delete("/it/storage")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getBoolean("deleted");
    }

    @Test
    void deleteRemovesTheObjects() throws Exception {
        JsonPath media = uploaded("bob", "/", "gone-" + token() + ".jpg", MediaFixtures.jpeg(400, 300));
        long id = media.getLong("id");
        List<String> urls = urls(media);
        assertThat(urls).hasSizeGreaterThan(1);
        for (String url : urls) {
            assertThat(fetch(url).statusCode()).as(url).isEqualTo(200);
        }
        as("bob").delete("/it/media/" + id).then().statusCode(200);
        for (String url : urls) {
            assertThat(fetch(url).statusCode()).as(url).isEqualTo(404);
        }
        as("bob").get("/it/media/" + id).then().statusCode(404);
    }
}
