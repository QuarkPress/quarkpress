package org.quarkpress.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.storage.fs.runtime.FsConfig;

/** All three filesystem keys are accepted at start-up; with a base URL the application serves no file itself. */
class FsBaseUrlDbTest {

    static final Path DIRECTORY = FsApp.directory("base-url");

    @RegisterExtension
    static final QuarkusExtensionTest app = FsApp.multisite(DIRECTORY, FsBaseUrlDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.fs.base-url", "https://cdn.example.test/u")
            .overrideRuntimeConfigKey("quarkpress.media.fs.path", "/media");

    @Inject
    FsConfig config;

    @Inject
    ActiveStorage active;

    @TestHTTPResource("/")
    URL root;

    @Test
    void allThreeKeysAreAccepted() {
        assertThat(config.directory()).contains(DIRECTORY);
        assertThat(config.baseUrl()).contains("https://cdn.example.test/u");
        assertThat(config.path()).isEqualTo("/media");
        SiteInfo site = new SiteInfo(SiteId.of(1), "localhost", "/", "Site", 1, false, false, false, false);
        assertThat(active.storage().publicUrl(site, "2026/11/a.jpg"))
                .isEqualTo("https://cdn.example.test/u/2026/11/a.jpg");
    }

    @Test
    void withABaseUrlNoRouteIsServed() throws IOException {
        Path source = Files.createDirectories(DIRECTORY.getParent().resolve("sources"))
                .resolve("base-url.jpg");
        Files.writeString(source, "jpeg bytes");
        if (!active.storage().exists("2026/11/a.jpg")) {
            active.storage().put("2026/11/a.jpg", source, "image/jpeg", WriteMode.CREATE_NEW);
        }
        assertThat(RawHttp.send(root, "GET", "/media/2026/11/a.jpg").status()).isEqualTo(404);
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/a.jpg")
                        .status())
                .isEqualTo(404);
        assertThat(Optional.ofNullable(
                        RawHttp.send(root, "GET", "/media/2026/11/a.jpg").header("X-Content-Type-Options")))
                .isEmpty();
    }
}
