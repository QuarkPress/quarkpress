package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The unchecked read of an attachment (of documents: without an image processor every image is refused), and the image
 * type rule without an image processor (the media module's own, which handles no type): refused before the file is
 * read unless the upload skips the check.
 */
class MediaReadDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.withoutStorage(MediaReadDbTest.class, TestStorage.class, TestMultisiteMode.class);

    static final String UNSUPPORTED_IMAGE = "The web server cannot generate responsive image sizes for this image."
            + " Convert it to JPEG or PNG before uploading.";

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    Posts posts;

    @Inject
    TestStorage storage;

    @Inject
    TestClock clock;

    SiteId site;
    UserId bob;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        site = TestData.siteIn(dataSource, "UTC");
        bob = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
    }

    private static Upload.Builder upload(String name, byte[] bytes, String type) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-read"));
            return Upload.builder(
                            name, Files.write(dir.resolve(UUID.randomUUID().toString()), bytes))
                    .declaredType(type);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private <T> T on(SiteId on, SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(on, identity, work);
    }

    private MediaSnapshot stored(SiteId on, Upload.Builder upload) throws Exception {
        return on(on, Identities.user(bob), () -> media.store(upload.build()));
    }

    @Test
    void readNeedsNoCapability() throws Exception {
        MediaSnapshot secret = stored(
                site,
                upload("secret.pdf", MediaFixtures.pdf(), "application/pdf").status("private"));
        PostId id = secret.post().id();
        assertThatThrownBy(() -> on(site, Identities.anonymous(), () -> media.find(id)))
                .isInstanceOf(UnauthorizedException.class);
        MediaSnapshot read =
                on(site, Identities.anonymous(), () -> media.read(id)).orElseThrow();
        assertThat(read)
                .isEqualTo(on(site, Identities.user(bob), () -> media.find(id)).orElseThrow());
        assertThat(read.post().status()).isEqualTo("private");
        assertThat(read.processing()).isEqualTo(Processing.PENDING);

        TestData.siteOption(dataSource, site, "qp_features", "{\"media\": false}");
        assertThatThrownBy(() -> on(site, Identities.anonymous(), () -> media.read(id)))
                .isInstanceOfSatisfying(
                        SiteFeatureDisabledException.class,
                        refusal -> assertThat(refusal.key()).isEqualTo("media"));
    }

    @Test
    void readIgnoresOtherPostsAndOtherSites() throws Exception {
        PostId page = on(
                        site,
                        Identities.user(bob),
                        () -> posts.create(PostChanges.builder()
                                .type("page")
                                .title("A page")
                                .status("publish")
                                .build()))
                .id();
        assertThat(on(site, Identities.anonymous(), () -> media.read(page))).isEmpty();

        SiteId other = TestData.siteIn(dataSource, "UTC");
        TestData.member(dataSource, other, bob, "[\"editor\"]");
        PostId elsewhere = stored(other, upload("x.pdf", MediaFixtures.pdf(), "application/pdf"))
                .post()
                .id();
        assertThat(on(site, Identities.user(bob), () -> media.read(elsewhere))).isEmpty();
        assertThat(on(other, Identities.anonymous(), () -> media.read(elsewhere)))
                .isPresent();

        // an attachment without a media row is no media
        PostId rowless = on(
                        site,
                        Identities.user(bob),
                        () -> posts.create(PostChanges.builder()
                                .type("attachment")
                                .status("inherit")
                                .title("no file")
                                .build()))
                .id();
        assertThat(on(site, Identities.anonymous(), () -> media.read(rowless))).isEmpty();
        assertThat(on(site, Identities.anonymous(), () -> media.read(PostId.of(Long.MAX_VALUE))))
                .isEmpty();
    }

    @Test
    void anUploadWithoutTheImageTypeCheckIsStored() throws Exception {
        assertThatThrownBy(() -> stored(site, upload("photo.webp", MediaFixtures.webp(), "image/webp")))
                .isInstanceOfSatisfying(MediaException.class, refusal -> {
                    assertThat(refusal.code()).isEqualTo("rest_upload_image_type_not_supported");
                    assertThat(refusal.getMessage()).isEqualTo(UNSUPPORTED_IMAGE);
                });
        assertThat(storage.keys()).isEmpty();

        MediaSnapshot uploaded = on(
                site,
                Identities.user(bob),
                () -> QuarkusTransaction.suspendingExisting()
                        .call(() -> media.upload(upload("photo.webp", MediaFixtures.webp(), "image/webp")
                                .imageTypeCheck(false)
                                .build())));
        assertThat(uploaded.mimeType()).isEqualTo("image/webp");
        assertThat(uploaded.sizes()).isEmpty();
        assertThat(storage.keys()).containsExactly(uploaded.storageKey());
    }

    @Test
    void refusedImageTypesFollowTheProcessor() throws Exception {
        assertThat(on(
                        site,
                        Identities.anonymous(),
                        () -> List.of(
                                media.refusesImageType("image/jpeg", true),
                                media.refusesImageType("image/jpeg", false),
                                media.refusesImageType("image/webp", true),
                                media.refusesImageType("image/heic", true),
                                media.refusesImageType("image/heif", true),
                                media.refusesImageType("image/svg+xml", true),
                                media.refusesImageType("application/pdf", true))))
                .containsExactly(true, false, true, false, false, false, false);
    }
}
