package org.quarkpress.it.mediavips;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.runtime.PendingProcessing;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The libvips image processor off the request, in the {@code async} processing mode: an upload's sizes are made by a
 * background run on a worker, and an upload an earlier run left unfinished is finished later, a BMP among them (decoded
 * by ImageIO, its sizes written by libvips as JPEG, its file kept). Its SQL names the main site (1) explicitly.
 */
@QuarkusTest
@SiteScopedNativeSql
@TestProfile(MediaVipsAsyncDbTest.Async.class)
@WithTestResource(MediaFilesResource.class)
class MediaVipsAsyncDbTest {

    /** The {@code async} processing mode. */
    public static class Async implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkpress.media.processing", "async");
        }
    }

    KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    ActiveStorage storage;

    @Inject
    PendingProcessing pending;

    @Inject
    QuarkPressClock clock;

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

    private JsonPath uploaded(String name, String type, byte[] bytes, Map<String, String> fields) {
        RequestSpecification request = as("bob").multiPart("file", name, bytes, type);
        fields.forEach(request::multiPart);
        Response response = request.post("/it/media");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        return response.jsonPath();
    }

    /** The attachment once its run has ended (neither pending nor partial), or as it is after 30 seconds. */
    private JsonPath settled(long id) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        JsonPath media;
        do {
            media = as("bob")
                    .get("/it/media/" + id)
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            String processing = media.getString("processing");
            if (!processing.equals("pending") && !processing.equals("partial")) {
                return media;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return media;
    }

    private static Map<String, String> files(JsonPath media) {
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        Map<String, String> files = new LinkedHashMap<>();
        sizes.forEach((name, size) -> files.put(name, (String) size.get("file")));
        return files;
    }

    private static final Map<String, String> BMP_SIZES = Map.of(
            "thumbnail", "%s-150x150.jpg",
            "medium", "%s-300x225.jpg",
            "medium_large", "%s-768x576.jpg");

    private static Map<String, String> bmpSizes(String stem) {
        Map<String, String> sizes = new LinkedHashMap<>();
        BMP_SIZES.forEach((name, file) -> sizes.put(name, file.formatted(stem)));
        return sizes;
    }

    @Test
    void asyncProcessingMakesTheSizesOffTheRequest() throws Exception {
        JsonPath stored = uploaded("later.jpg", "image/jpeg", MediaFixtures.jpeg(4000, 3000), Map.of());
        assertThat(stored.getString("processing")).isEqualTo("pending");
        assertThat(stored.getMap("sizes")).isEmpty();
        JsonPath media = settled(stored.getLong("id"));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("attachedFile")).endsWith("/later-scaled.jpg");
        assertThat(media.getMap("sizes")).hasSize(6);
    }

    @Test
    void aBmpLeftPartialIsFinishedWithJpegSizesAndKeepsItsFile() throws Exception {
        // stored without sizes, then as a run that stopped half-way leaves it: partial, without a converted copy
        long id = uploaded(
                        "half-done.bmp",
                        "image/bmp",
                        MediaFixtures.bmp(800, 600),
                        Map.of("generate_sub_sizes", "false"))
                .getLong("id");
        assertThat(settled(id).getString("processing")).isEqualTo("done");
        update("update qp_media set processing = 'partial' where site_id = 1 and post_id = ?", id);
        JsonPath processed = as("bob")
                .post("/it/media/" + id + "/process")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(processed.getString("processing")).isEqualTo("done");
        assertThat(processed.getString("mimeType")).isEqualTo("image/bmp");
        assertThat(processed.getString("attachedFile")).endsWith("/half-done.bmp");
        assertThat(processed.getString("originalImage")).isNull();
        assertThat(files(processed)).containsExactlyInAnyOrderEntriesOf(bmpSizes("half-done"));
    }

    @Test
    void aBmpLeftPendingIsResumedWithJpegSizes() throws Exception {
        long id = pendingBmp("left-pending.bmp", 800, 600);
        // as at the next start, once the upload is older than five minutes
        pending.resume(clock.now());
        JsonPath media = as("bob")
                .get("/it/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("attachedFile")).isEqualTo("2026/11/left-pending.bmp");
        assertThat(media.getString("originalImage")).isNull();
        assertThat(files(media)).containsExactlyInAnyOrderEntriesOf(bmpSizes("left-pending"));
    }

    /**
     * A BMP an earlier run stored but left pending: the file in the storage, the post on the main site created ten
     * minutes ago, the media row pending. Answers the post id.
     */
    private long pendingBmp(String name, int width, int height) throws Exception {
        byte[] bytes = MediaFixtures.bmp(width, height);
        String key = "2026/11/" + name;
        Path file = Files.createTempFile(Path.of("target"), "pending-", ".tmp");
        try {
            Files.write(file, bytes);
            storage.storage().put(key, file, "image/bmp", WriteMode.CREATE_NEW);
        } finally {
            Files.deleteIfExists(file);
        }
        boolean postgres = kind().equals("postgresql");
        String now = postgres ? "now()" : "utc_timestamp(6)";
        String json = postgres ? "cast(? as json)" : "?";
        String guid = "guid-" + UUID.randomUUID();
        update(
                "insert into qp_post (site_id, type, status, slug, title, content, excerpt, created_at, modified_at,"
                        + " comment_status, ping_status, guid) values (1, 'attachment', 'inherit', ?, ?, '', '', "
                        + now + ", " + now + ", 'open', 'open', ?)",
                name,
                name,
                guid);
        long post = single("select id from qp_post where site_id = 1 and guid = ?", guid);
        update("update qp_post set created_at = created_at - interval '10' minute where site_id = 1 and id = ?", post);
        update(
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, width, height, sizes,"
                        + " image_meta, extra, alt_text, processing, stored_bytes, version) values (?, 1, ?,"
                        + " 'image/bmp', ?, " + width + ", " + height + ", " + json + ", null, " + json
                        + ", '', 'pending', ?, 0)",
                post,
                key,
                (long) bytes.length,
                "{}",
                "{}",
                (long) bytes.length);
        return post;
    }

    private static String kind() {
        String kind = System.getenv("QUARKPRESS_TEST_DB");
        return kind == null || kind.isBlank() ? "postgresql" : kind;
    }

    private void update(String sql, Object... params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params)) {
            statement.executeUpdate();
        }
    }

    private long single(String sql, Object... params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as(sql).isTrue();
            return rows.getLong(1);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
