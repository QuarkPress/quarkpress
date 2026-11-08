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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Only an attachment an image of size {@code thumbnail} can be made for becomes a featured image: any stored image
 * type does, audio, video and documents (no previews are made of PDFs) do not, nor does an attachment without file.
 */
class MediaThumbnailsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(MediaThumbnailsDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.svg", "image/svg+xml");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    Posts posts;

    @Inject
    FeaturedImages images;

    SiteId site;
    UserId bob;

    @BeforeEach
    void setUp() {
        // a single site: no network list of upload types narrows the allowed ones
        site = TestData.MAIN;
        bob = TestData.memberOfMain(dataSource, "editor");
    }

    @Test
    void onlyImagesBecomeFeaturedImages() throws Exception {
        PostId post = asBob(() -> posts.create(PostChanges.builder()
                        .type("post")
                        .status("publish")
                        .title("Pictured")
                        .build()))
                .id();
        long jpeg = stored("photo.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg");
        long svg = stored("logo.svg", MediaFixtures.text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "image/svg+xml");
        long heic = stored("trip.heic", MediaFixtures.heif("heic"), "image/heic");
        long tiff = stored("scan.tif", MediaFixtures.tiff(8, 8), "image/tiff");
        long mp3 = stored("song.mp3", MediaFixtures.mp3(Map.of(), Optional.empty()), "audio/mpeg");
        long pdf = stored("paper.pdf", MediaFixtures.pdf(), "application/pdf");
        long rowless = asBob(() -> posts.create(PostChanges.builder()
                        .type("attachment")
                        .status("inherit")
                        .title("no file")
                        .build()))
                .id()
                .value();
        long keyless = stored("blank.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg");
        Jdbc.update(dataSource, "update qp_media set storage_key = '' where post_id = ?", keyless);

        assertThat(set(post, jpeg)).isTrue();
        // any image type is kept
        for (long image : new long[] {svg, heic, tiff}) {
            assertThat(set(post, image)).isTrue();
            assertThat(featured(post)).isEqualTo(image);
        }
        // anything else removes the featured image, as a post that is no attachment does
        for (long other : new long[] {mp3, pdf, rowless, keyless}) {
            assertThat(set(post, jpeg)).isTrue();
            assertThat(set(post, other)).isTrue();
            assertThat(featured(post)).isZero();
            assertThat(set(post, other)).isFalse();
        }
    }

    private long stored(String name, byte[] bytes, String type) throws Exception {
        Path dir;
        try {
            dir = Files.createDirectories(Path.of("target", "media-thumbnails"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        return asBob(() -> media.store(Upload.builder(name, file)
                                .declaredType(type)
                                .imageTypeCheck(false)
                                .build())
                        .post()
                        .id())
                .value();
    }

    private boolean set(PostId post, long attachment) throws Exception {
        return asBob(() -> images.set(post, attachment));
    }

    private long featured(PostId post) throws Exception {
        return asBob(() -> FeaturedImages.of(posts.find(post).orElseThrow()));
    }

    private <T> T asBob(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(bob), work);
    }
}
