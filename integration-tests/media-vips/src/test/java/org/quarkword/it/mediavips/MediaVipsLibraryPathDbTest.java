package org.quarkword.it.mediavips;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * libvips configured by its absolute path ({@code quarkus.libvips.library}): the readiness check names the library by
 * its file name only, so no directory of the host reaches a client.
 */
@QuarkusTest
@TestProfile(MediaVipsLibraryPathDbTest.LibraryPath.class)
@WithTestResource(MediaFilesResource.class)
class MediaVipsLibraryPathDbTest {

    /** {@code quarkus.libvips.library} set to the absolute path {@code ldconfig} gives for {@code libvips.so.42}. */
    public static class LibraryPath implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.libvips.library", libraryPath());
        }
    }

    @Test
    void theReadinessCheckNamesAConfiguredPathByItsFileName() {
        assertThat(Path.of(libraryPath())).isAbsolute().exists();
        JsonPath health = given().header("Host", "localhost")
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        Map<String, Object> libvips = health.getMap("checks.find { it.name == 'libvips' }");
        assertThat(libvips).containsEntry("status", "UP");
        Map<String, Object> data = health.getMap("checks.find { it.name == 'libvips' }.data");
        assertThat(data).containsEntry("available", true).containsEntry("library", "libvips.so.42");
        assertThat(data.values())
                .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("/"));
    }

    /** The absolute path of {@code libvips.so.42} in {@code ldconfig}'s cache. */
    static String libraryPath() {
        for (String ldconfig : new String[] {"/sbin/ldconfig", "/usr/sbin/ldconfig"}) {
            if (!Files.isExecutable(Path.of(ldconfig))) {
                continue;
            }
            try {
                Process process = new ProcessBuilder(ldconfig, "-p")
                        .redirectErrorStream(true)
                        .start();
                String cache = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                for (String line : cache.split("\n")) {
                    int arrow = line.indexOf("=> ");
                    if (line.trim().startsWith("libvips.so.42 ") && arrow > 0) {
                        return line.substring(arrow + 3).trim();
                    }
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        throw new IllegalStateException("libvips.so.42 is not in ldconfig's cache");
    }
}
