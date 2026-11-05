package org.quarkpress.media.storage.fs.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Test applications with the media module and this filesystem backend on QuarkPress's datasource (the media test
 * settings); Dev Services starts the database.
 */
public final class FsApp {

    private FsApp() {}

    /** An application in multisite mode whose files live in {@code directory} (emptied first), below target/. */
    public static QuarkusExtensionTest multisite(Path directory, Class<?>... classes) {
        empty(directory);
        return app(classes).overrideRuntimeConfigKey("quarkpress.media.fs.directory", directory.toString());
    }

    /** An application in multisite mode without a configured directory. */
    public static QuarkusExtensionTest app(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(FsApp.class, TestDb.class, Failures.class, RawHttp.class, TestMultisiteMode.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }

    /** A directory below target/ for one test class. */
    public static Path directory(String name) {
        return Path.of("target", "fs-apps", name).toAbsolutePath();
    }

    private static void empty(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
