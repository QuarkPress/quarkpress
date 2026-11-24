package org.quarkword.it.mediavips;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.wprestparity.MediaFixtures;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.SaveOptions;

/**
 * A real, large PNG through the upload size limit raised for it: an uncompressed 8400&times;8400 RGB PNG of about 202
 * MiB, made by libvips in the test, below the raised HTTP body limit (256M) and its 70 560 000 pixels within
 * {@code max-image-pixels}, so it is accepted and gets its sizes. Heavy (the file, its decode and its copies), so it
 * runs only with {@code -Dvips.large-png=true} (a name outside {@code quarkword.*}, which the application reserves for
 * its own settings and checks at start-up).
 */
@QuarkusTest
@TestProfile(MediaVipsLargePngDbTest.LargeUploads.class)
@WithTestResource(MediaFilesResource.class)
@EnabledIfSystemProperty(named = "vips.large-png", matches = "true")
class MediaVipsLargePngDbTest {

    static final int SIDE = 8400;

    /** The HTTP body limit raised to 256M; libvips's input size limit rises with it. */
    public static class LargeUploads implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.limits.max-body-size", "256M");
        }
    }

    KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    Libvips libvips;

    private Path png;

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

    @AfterEach
    void removeTheFile() throws IOException {
        if (png != null) {
            Files.deleteIfExists(png);
        }
    }

    @Test
    void aLargeUncompressedPngIsAcceptedAndGetsItsSizes() throws IOException {
        png = Files.createTempFile(Files.createDirectories(Path.of("target", "large-png")), "large-", ".png");
        // a black SIDE x SIDE RGB image written without compression
        try (Image small = libvips.load(MediaFixtures.png(8, 8));
                Image large = small.call("embed", Map.of("x", 0, "y", 0, "width", SIDE, "height", SIDE))) {
            Files.write(png, large.save(new SaveOptions.Png(0, false, Set.of())));
        }
        long bytes = Files.size(png);
        assertThat(bytes).isBetween(200L * 1024 * 1024, 256L * 1024 * 1024);
        Response response =
                as("bob").multiPart("file", png.toFile(), "image/png").post("/it/media");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        JsonPath media = response.jsonPath();
        String stem = media.getString("originalImage").replace(".png", "");
        assertThat(media.getString("processing")).isEqualTo("done");
        // the upload is kept as the original; the main file (and its fileSize) is the scaled copy, as in WordPress
        assertThat(media.getString("originalImage")).isEqualTo(png.getFileName().toString());
        assertThat(media.getString("attachedFile")).endsWith("/" + stem + "-scaled.png");
        assertThat(media.getInt("width")).isEqualTo(2560);
        assertThat(media.getInt("height")).isEqualTo(2560);
        assertThat(MediaVipsDbTest.files(media))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", stem + "-150x150.png",
                        "medium", stem + "-300x300.png",
                        "medium_large", stem + "-768x768.png",
                        "large", stem + "-1024x1024.png",
                        "1536x1536", stem + "-1536x1536.png",
                        "2048x2048", stem + "-2048x2048.png"));
        JsonPath images = as(null).get("/it/vips/open-images")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(images.getLong("open")).isZero();
        assertThat(images.getLong("leaked")).isZero();
    }
}
