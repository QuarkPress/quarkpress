package org.quarkpress.it.medias3;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The AWS SDK calls that the S3 media storage needs, made by the application ({@code /it/s3-spike}) against moto
 * started by Dev Services: a put from a file with its content type, a conditional put on a taken key, a streamed get,
 * a head, a one-level listing, a delete and a presigned GET. JVM mode here; {@code S3SpikeIT} runs the same tests
 * against the packaged application, and against the native executable in CI.
 *
 * <p>Each test writes under its own random folder, so the tests do not depend on each other or on an earlier run.
 */
@QuarkusTest
class S3SpikeDbTest {

    private static final byte[] HELLO = "Hello, S3!\n".getBytes(StandardCharsets.UTF_8);

    private static String folder() {
        return "spike-" + UUID.randomUUID();
    }

    private static void put(String key, String contentType, byte[] body) {
        given().queryParam("key", key)
                .queryParam("contentType", contentType)
                .contentType("application/octet-stream")
                .body(body)
                .post("/it/s3-spike/put")
                .then()
                .statusCode(200);
    }

    @Test
    void putFromFileThenGetStreamsTheBytesWithTheContentType() {
        String key = folder() + "/2026/11/hello.txt";
        put(key, "text/plain", HELLO);

        byte[] read = given().queryParam("key", key)
                .get("/it/s3-spike/object")
                .then()
                .statusCode(200)
                .header("X-S3-Content-Type", "text/plain")
                .extract()
                .asByteArray();
        assertThat(read).isEqualTo(HELLO);
    }

    @Test
    void conditionalPutOnATakenKeyFailsWith412() {
        String key = folder() + "/taken.bin";

        JsonPath first = given().queryParam("key", key)
                .contentType("application/octet-stream")
                .body(HELLO)
                .post("/it/s3-spike/put-new")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(first.getBoolean("written")).isTrue();

        JsonPath second = given().queryParam("key", key)
                .contentType("application/octet-stream")
                .body(new byte[] {1, 2, 3})
                .post("/it/s3-spike/put-new")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(second.getBoolean("written")).isFalse();
        assertThat(second.getInt("status")).isEqualTo(412);
        assertThat(second.getString("code")).isEqualTo("PreconditionFailed");

        // The first object is untouched.
        byte[] read = given().queryParam("key", key)
                .get("/it/s3-spike/object")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertThat(read).isEqualTo(HELLO);
    }

    @Test
    void headGivesTheSizeAndAMissingKeyIsNotFound() {
        String key = folder() + "/sized.bin";
        put(key, "application/octet-stream", new byte[1234]);

        JsonPath head = given().queryParam("key", key)
                .get("/it/s3-spike/head")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(head.getLong("size")).isEqualTo(1234L);
        assertThat(head.getString("contentType")).isEqualTo("application/octet-stream");

        given().queryParam("key", key + ".missing")
                .get("/it/s3-spike/head")
                .then()
                .statusCode(404);
        given().queryParam("key", key + ".missing")
                .get("/it/s3-spike/object")
                .then()
                .statusCode(404);
    }

    @Test
    void listGivesOneLevelOfFilesAndFolders() {
        String root = folder();
        put(root + "/a.txt", "text/plain", HELLO);
        put(root + "/b.txt", "text/plain", HELLO);
        put(root + "/2026/11/c.jpg", "image/jpeg", HELLO);
        put(root + "/2026/12/d.jpg", "image/jpeg", HELLO);
        put(root + "/sites/2/e.png", "image/png", HELLO);

        JsonPath top = given().queryParam("prefix", root + "/")
                .get("/it/s3-spike/list")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(top.getList("files", String.class)).containsExactly(root + "/a.txt", root + "/b.txt");
        assertThat(top.getList("folders", String.class)).containsExactly(root + "/2026/", root + "/sites/");

        JsonPath year = given().queryParam("prefix", root + "/2026/")
                .get("/it/s3-spike/list")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(year.getList("files", String.class)).isEmpty();
        assertThat(year.getList("folders", String.class)).containsExactly(root + "/2026/11/", root + "/2026/12/");

        JsonPath none = given().queryParam("prefix", root + "/nothing/")
                .get("/it/s3-spike/list")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(none.getList("files", String.class)).isEmpty();
        assertThat(none.getList("folders", String.class)).isEmpty();
    }

    @Test
    void deleteRemovesTheObject() {
        String key = folder() + "/gone.txt";
        put(key, "text/plain", HELLO);

        given().queryParam("key", key).delete("/it/s3-spike/object").then().statusCode(204);

        given().queryParam("key", key).get("/it/s3-spike/head").then().statusCode(404);
        given().queryParam("key", key).get("/it/s3-spike/object").then().statusCode(404);
        // S3 answers a delete of a missing key with success too.
        given().queryParam("key", key).delete("/it/s3-spike/object").then().statusCode(204);
    }

    @Test
    void aPresignedGetUrlServesTheBytes() throws IOException {
        String key = folder() + "/presigned.txt";
        put(key, "text/plain", HELLO);

        String url = given().queryParam("key", key)
                .queryParam("seconds", 600)
                .get("/it/s3-spike/presign")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("url");
        assertThat(url)
                .contains("X-Amz-Signature=")
                .contains("/quarkpress-media/")
                .contains(key);

        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            assertThat(connection.getContentType()).isEqualTo("text/plain");
            try (InputStream in = connection.getInputStream()) {
                assertThat(in.readAllBytes()).isEqualTo(HELLO);
            }
        } finally {
            connection.disconnect();
        }
    }
}
