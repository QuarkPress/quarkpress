package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * What a sideloaded file (one the server fetched from a URL) names its attachment, as WordPress's sideload does: the
 * title is the stored file name's stem unless the file embeds a usable title, an embedded caption becomes the
 * description rather than the caption, and an embedded alternative text is stored.
 */
class SideloadDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
            SideloadDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class);

    static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    @Inject
    TestProcessor processor;

    @Inject
    TestClock clock;

    SiteId site;
    UserId carol;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        site = TestData.siteIn(dataSource, "UTC");
        carol = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, carol, "[\"author\"]");
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "sideload"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder sideloaded(String name, byte[] bytes) {
        return Upload.builder(name, file(bytes)).declaredType("image/jpeg").sideloaded();
    }

    private MediaSnapshot stored(Upload.Builder upload) throws Exception {
        return Identities.onSite(site, Identities.user(carol), () -> media.store(upload.build()));
    }

    @Test
    void titleIsTheStoredStem() throws Exception {
        MediaSnapshot first = stored(sideloaded("photo.jpg", MediaFixtures.jpeg(8, 8)));
        MediaSnapshot second = stored(sideloaded("photo.jpg", MediaFixtures.jpeg(8, 8)));
        assertThat(first.post().title()).isEqualTo("photo");
        assertThat(first.storageKey()).endsWith("/2026/11/photo.jpg");
        assertThat(second.post().title()).isEqualTo("photo-1");
        assertThat(second.storageKey()).endsWith("/2026/11/photo-1.jpg");
        assertThat(second.post().slug()).isEqualTo("photo-1");
        // the stem is the stored name's: cleaned, and with the extension the content corrected
        assertThat(stored(sideloaded("../../up a.jpg", MediaFixtures.jpeg(8, 8)))
                        .post()
                        .title())
                .isEqualTo("up-a");
        MediaSnapshot png =
                stored(sideloaded("drawing.jpg", MediaFixtures.png(8, 8)).declaredType("image/png"));
        assertThat(png.post().title()).isEqualTo("drawing");
        assertThat(png.storageKey()).endsWith("/drawing.png");
        // a title the request gives still wins
        assertThat(stored(sideloaded("photo.jpg", MediaFixtures.jpeg(8, 8)).title("Given"))
                        .post()
                        .title())
                .isEqualTo("Given");
    }

    @Test
    void captionGoesToTheDescription() throws Exception {
        byte[] captioned = MediaFixtures.jpeg(8, 8, JpegMeta.none().withIptc(120, "Cap"));
        PostSnapshot post = stored(sideloaded("captioned.jpg", captioned)).post();
        assertThat(post.content()).as("description").isEqualTo("Cap");
        assertThat(post.excerpt()).as("caption").isEmpty();
        // a description the request gives wins over the embedded caption; a requested caption is kept as given
        PostSnapshot given = stored(sideloaded("captioned.jpg", captioned)
                        .description("Desc")
                        .caption("Given caption"))
                .post();
        assertThat(given.content()).isEqualTo("Desc");
        assertThat(given.excerpt()).isEqualTo("Given caption");
        // an upload that is not a sideload keeps the embedded caption as the caption
        PostSnapshot upload = stored(
                        Upload.builder("captioned.jpg", file(captioned)).declaredType("image/jpeg"))
                .post();
        assertThat(upload.excerpt()).isEqualTo("Cap");
        assertThat(upload.content()).isEmpty();
    }

    @Test
    void aNumericEmbeddedTitleIsIgnored() throws Exception {
        byte[] numbered = MediaFixtures.jpeg(8, 8, JpegMeta.none().withIptc(5, "123"));
        assertThat(stored(sideloaded("counter.jpg", numbered)).post().title()).isEqualTo("counter");
        // a number once trimmed and its slug made counts as numeric too
        byte[] dated = MediaFixtures.jpeg(8, 8, JpegMeta.none().withIptc(5, " 20250612 "));
        assertThat(stored(sideloaded("dated.jpg", dated)).post().title()).isEqualTo("dated");
        // a title that is a text replaces the stem, and an embedded alternative text is stored
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<Iptc4xmpCore:AltTextAccessibility><rdf:Alt><rdf:li xml:lang=\"x-default\">A red kite</rdf:li>"
                + "</rdf:Alt></Iptc4xmpCore:AltTextAccessibility></rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] titled =
                MediaFixtures.jpeg(8, 8, JpegMeta.none().withIptc(105, "Sunset").withXmp(xmp));
        MediaSnapshot sunset = stored(sideloaded("x.jpg", titled));
        assertThat(sunset.post().title()).isEqualTo("Sunset");
        assertThat(sunset.altText()).isEqualTo("A red kite");
    }
}
