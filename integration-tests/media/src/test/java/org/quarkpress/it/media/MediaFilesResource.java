package org.quarkpress.it.media;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A new, empty storage directory for each start of the application under test ({@code target/media-files/<uuid>},
 * absolute), so that the stored file names never depend on an earlier run, and its removal when the tests end. In
 * {@code @QuarkusTest} the setting overrides the application's configuration; in {@code @QuarkusIntegrationTest}
 * Quarkus passes it to the launched jar or native executable as a system property.
 */
public class MediaFilesResource implements QuarkusTestResourceLifecycleManager {

    private Path directory;

    @Override
    public Map<String, String> start() {
        try {
            directory = Files.createDirectories(
                    Path.of("target", "media-files", UUID.randomUUID().toString())
                            .toAbsolutePath());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return Map.of("quarkpress.media.fs.directory", directory.toString());
    }

    @Override
    public void stop() {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
