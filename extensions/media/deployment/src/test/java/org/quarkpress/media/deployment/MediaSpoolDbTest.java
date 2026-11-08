package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.Media;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * A request body spooled into the storage's temporary area: with a storage that keeps no local files, a file in the
 * JVM's temporary folder, which the caller deletes.
 */
class MediaSpoolDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(MediaSpoolDbTest.class, TestStorage.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    private Path spool(SiteId site, InputStream body) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> media.spool(body));
    }

    /** The spool files in the JVM's temporary folder. */
    private static Set<Path> spoolFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("quarkpress-media-"))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void aSpooledBodyIsAFileTheCallerDeletes() throws Exception {
        byte[] bytes = MediaFixtures.jpeg(64, 48);
        Path spooled = spool(TestData.MAIN, new ByteArrayInputStream(bytes));
        assertThat(spooled.getParent())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
        assertThat(spooled.getFileName().toString())
                .startsWith("quarkpress-media-")
                .endsWith(".tmp");
        assertThat(Files.readAllBytes(spooled)).isEqualTo(bytes);
        // nothing went into the storage itself
        assertThat(storage.keys()).isEmpty();
        Files.delete(spooled);
        assertThat(spooled).doesNotExist();
        assertThat(spoolFiles()).doesNotContain(spooled);
    }

    @Test
    void aBodyThatFailsLeavesNoFile() throws Exception {
        Set<Path> before = spoolFiles();
        IOException failure = new IOException("the client went away");
        InputStream broken = new SequenceInputStream(new ByteArrayInputStream(new byte[8192]), new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        });
        assertThatThrownBy(() -> spool(TestData.MAIN, broken)).isSameAs(failure);
        assertThat(spoolFiles()).isSubsetOf(before);
    }

    @Test
    void theMediaFeatureMustBeOn() throws Exception {
        SiteId off = TestData.site(dataSource);
        TestData.siteOption(dataSource, off, "qp_features", "{\"media\": false}");
        Set<Path> before = spoolFiles();
        assertThatThrownBy(() -> spool(off, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(SiteFeatureDisabledException.class);
        assertThat(spoolFiles()).isSubsetOf(before);
    }
}
