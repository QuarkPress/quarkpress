package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The post fields an upload and an update carry: the date, the slug and the comment and ping statuses, saved by the
 * posts module's rules (a date never schedules an attachment; the comment status defaults to the site's option, the
 * ping status to closed).
 */
class MediaUploadFieldsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
            MediaUploadFieldsDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

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
        site = TestData.siteIn(dataSource, "Europe/Paris");
        carol = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, carol, "[\"author\"]");
    }

    private static Upload.Builder jpeg(String name) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-upload-fields"));
            Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), MediaFixtures.jpeg(8, 8));
            return Upload.builder(name, file).declaredType("image/jpeg");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private <T> T asCarol(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(carol), work);
    }

    private MediaSnapshot stored(Upload.Builder upload) throws Exception {
        return asCarol(() -> media.store(upload.build()));
    }

    @Test
    void postFieldsOfAnUploadAreStored() throws Exception {
        PostSnapshot post = stored(jpeg("photo.jpg")
                        .date(LocalDateTime.parse("2019-03-14T10:00:00"))
                        .slug("my-photo")
                        .commentStatus("closed")
                        .pingStatus("open"))
                .post();
        assertThat(post.localDate(PARIS)).isEqualTo(LocalDateTime.parse("2019-03-14T10:00:00"));
        assertThat(post.publishedAt()).contains(Instant.parse("2019-03-14T09:00:00Z"));
        assertThat(post.status()).isEqualTo("inherit");
        assertThat(post.slug()).isEqualTo("my-photo");
        assertThat(post.commentStatus()).isEqualTo("closed");
        assertThat(post.pingStatus()).isEqualTo("open");

        // a date to come never schedules an attachment; the UTC date is taken as given
        PostSnapshot later = stored(jpeg("later.jpg").dateGmt(Instant.parse("2030-01-01T00:00:00Z")))
                .post();
        assertThat(later.status()).isEqualTo("inherit");
        assertThat(later.publishedAt()).contains(Instant.parse("2030-01-01T00:00:00Z"));

        // the slug is made unique as any post's, and the processed upload carries the fields too
        MediaSnapshot uploaded = asCarol(() -> QuarkusTransaction.suspendingExisting()
                .call(() -> media.upload(
                        jpeg("again.jpg").slug("my-photo").pingStatus("open").build())));
        assertThat(uploaded.post().slug()).isEqualTo("my-photo-2");
        assertThat(uploaded.post().pingStatus()).isEqualTo("open");
    }

    @Test
    void absentFieldsTakeTheDefaults() throws Exception {
        PostSnapshot post = stored(jpeg("photo.jpg")).post();
        assertThat(post.commentStatus()).isEqualTo("open");
        assertThat(post.pingStatus()).isEqualTo("closed");
        assertThat(post.slug()).isEqualTo("photo");
        assertThat(post.publishedAt()).contains(NOW);

        // the comment status follows the site's option
        TestData.siteOption(dataSource, site, "default_comment_status", "\"closed\"");
        TestData.siteOption(dataSource, site, "default_ping_status", "\"open\"");
        PostSnapshot closed = stored(jpeg("other.jpg")).post();
        assertThat(closed.commentStatus()).isEqualTo("closed");
        assertThat(closed.pingStatus()).isEqualTo("closed");
    }

    @Test
    void updateCarriesTheSameFields() throws Exception {
        MediaSnapshot stored = stored(jpeg("photo.jpg"));
        PostSnapshot updated = asCarol(() -> media.update(
                        stored.post().id(),
                        MediaChanges.builder()
                                .slug("renamed")
                                .pingStatus("open")
                                .commentStatus("closed")
                                .date(LocalDateTime.parse("2019-03-14T10:00:00"))
                                .build()))
                .post();
        assertThat(updated.slug()).isEqualTo("renamed");
        assertThat(updated.pingStatus()).isEqualTo("open");
        assertThat(updated.commentStatus()).isEqualTo("closed");
        assertThat(updated.localDate(PARIS)).isEqualTo(LocalDateTime.parse("2019-03-14T10:00:00"));
        assertThat(updated.status()).isEqualTo("inherit");

        PostSnapshot dated = asCarol(() -> media.update(
                        stored.post().id(),
                        MediaChanges.builder()
                                .dateGmt(Instant.parse("2020-05-01T12:00:00Z"))
                                .build()))
                .post();
        assertThat(dated.publishedAt()).contains(Instant.parse("2020-05-01T12:00:00Z"));
        assertThat(dated.slug()).isEqualTo("renamed");
        assertThat(dated.pingStatus()).isEqualTo("open");
    }
}
