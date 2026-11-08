package org.quarkpress.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.Media;
import org.quarkpress.persistence.SiteScope;

/** With the filesystem backend, a spooled request body lands in the storage's own temporary folder. */
class FsSpoolDbTest {

    static final Path DIRECTORY = FsApp.directory("spool");

    @RegisterExtension
    static final QuarkusExtensionTest app = FsApp.multisite(DIRECTORY, FsSpoolDbTest.class);

    @Inject
    Media media;

    @Inject
    SiteScope siteScope;

    @Test
    void aSpooledBodyLandsInTheStoragesTemporaryArea() throws Exception {
        byte[] bytes = "a raw request body".getBytes(StandardCharsets.US_ASCII);
        Path spooled = siteScope.call(SiteId.of(1), () -> media.spool(new ByteArrayInputStream(bytes)));
        assertThat(spooled.getParent()).isEqualTo(DIRECTORY.resolve(".tmp"));
        assertThat(Files.readAllBytes(spooled)).isEqualTo(bytes);
        Files.delete(spooled);
        assertThat(spooled).doesNotExist();
    }
}
