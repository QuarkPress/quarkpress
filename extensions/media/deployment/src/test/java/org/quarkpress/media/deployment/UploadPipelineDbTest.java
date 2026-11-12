package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.quarkpress.media.MediaStored;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.media.UploadCheck;
import org.quarkpress.media.UploadFilter;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDraft;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostWriteContext;
import org.quarkpress.posts.PostWriteFilter;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * Storing an upload: WordPress's checks in its order, the stored file, the attachment post and its media row, the
 * names, the refusals before any write, the rollback after one, and the announcement.
 */
class UploadPipelineDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    UploadPipelineDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    Recorder.class,
                    BlockingFilter.class,
                    BoomFilter.class)
            .overrideConfigKey("quarkpress.media.max-upload-size", "64K");

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final SiteId MAIN = TestData.MAIN;
    static final String NOT_ALLOWED = "Sorry, you are not allowed to upload this file type.";
    static final String UNSUPPORTED = "The web server cannot generate responsive image sizes for this image. Convert"
            + " it to JPEG or PNG before uploading.";
    static final String MOVE_REFUSED = "The uploaded file could not be moved to wp-content/uploads/2026/11.";

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
    TestProcessor processor;

    @Inject
    TestClock clock;

    @Inject
    Recorder recorder;

    @Inject
    TransactionManager transactions;

    UserId carol;
    UserId bob;
    UserId dave;

    /** Records the announcements and what an asynchronous observer finds in the database (no client proxy). */
    @Singleton
    public static class Recorder {
        final List<MediaStored> stored = new CopyOnWriteArrayList<>();
        final Map<Long, CompletableFuture<Long>> rowsSeenAfterCommit = new ConcurrentHashMap<>();
        final List<UploadCheck> checks = new CopyOnWriteArrayList<>();

        @Inject
        @DataSource("quarkpress")
        AgroalDataSource dataSource;

        void stored(@Observes MediaStored event) {
            stored.add(event);
            if (event.media().post().title().equals("late boom")) {
                throw new IllegalStateException("a synchronous observer refused the upload");
            }
        }

        void storedAfterCommit(@ObservesAsync MediaStored event) {
            long id = event.media().post().id().value();
            long rows = Jdbc.count(dataSource, "select count(*) from qp_media where post_id = ?", id);
            rowsSeenAfterCommit
                    .computeIfAbsent(id, key -> new CompletableFuture<>())
                    .complete(rows);
        }
    }

    /** Refuses uploads named {@code blocked.txt}, as a site's policy might. */
    @ApplicationScoped
    public static class BlockingFilter implements UploadFilter {
        @Inject
        Recorder recorder;

        @Override
        public Optional<String> refuse(UploadCheck upload) {
            recorder.checks.add(upload);
            return upload.clientName().equals("blocked.txt") ? Optional.of("Blocked by policy.") : Optional.empty();
        }
    }

    /** Makes the insert of a post titled {@code boom} fail once the file is stored. */
    @ApplicationScoped
    public static class BoomFilter implements PostWriteFilter {
        @Override
        public PostDraft apply(PostDraft draft, PostWriteContext context) {
            if (draft.title().equals("boom")) {
                throw new PostException("boom", "The test filter refused the post.");
            }
            return draft;
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        recorder.stored.clear();
        recorder.checks.clear();
        TestData.siteOption(dataSource, MAIN, "timezone_string", "\"UTC\"");
        TestData.siteOption(dataSource, MAIN, "WPLANG", "\"\"");
        carol = TestData.memberOfMain(dataSource, "author");
        bob = TestData.memberOfMain(dataSource, "editor");
        dave = TestData.memberOfMain(dataSource, "contributor");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "upload-pipeline"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder upload(String name, byte[] bytes, String declaredType) {
        return Upload.builder(name, file(bytes)).declaredType(declaredType);
    }

    private static Upload.Builder upload(String name, byte[] bytes) {
        return Upload.builder(name, file(bytes));
    }

    private <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(MAIN, identity, work);
    }

    private MediaSnapshot as(UserId user, Upload.Builder upload) throws Exception {
        return as(Identities.user(user), () -> media.store(upload.build()));
    }

    private MediaSnapshot asCarol(Upload.Builder upload) throws Exception {
        return as(carol, upload);
    }

    private MediaSnapshot store(String name) throws Exception {
        return asCarol(upload(name, MediaFixtures.jpeg(8, 8), "image/jpeg"));
    }

    private MediaSnapshot store(byte[] jpeg, String name) throws Exception {
        return asCarol(upload(name, jpeg, "image/jpeg"));
    }

    private MediaSnapshot storeText(String name) throws Exception {
        return asCarol(upload(name, MediaFixtures.text("some text\n"), "text/plain"));
    }

    private static byte[] jpeg(JpegMeta meta) {
        return MediaFixtures.jpeg(16, 12, meta);
    }

    private long attachments(SiteId site) {
        return Jdbc.count(
                dataSource, "select count(*) from qp_post where site_id = ? and type = 'attachment'", site.value());
    }

    private long mediaRows() {
        return Jdbc.count(dataSource, "select count(*) from qp_media");
    }

    /** {@code work} is refused as {@code expected} describes, and nothing is stored or written. */
    private void assertNothingWritten(
            ThrowingRunnable work, Class<? extends Throwable> type, String code, String message) throws Exception {
        List<String> keys = storage.keys();
        long posts = attachments(MAIN);
        long rows = mediaRows();
        int announced = recorder.stored.size();
        int puts = storage.puts();
        assertThatThrownBy(work::run).isInstanceOfSatisfying(type, refusal -> {
            if (code != null) {
                assertThat(((MediaException) refusal).code()).isEqualTo(code);
            }
            if (message != null) {
                assertThat(refusal).hasMessage(message);
            }
        });
        // no file was stored at all, not even one that a rollback removed again
        assertThat(storage.puts()).as("files stored").isEqualTo(puts);
        assertThat(storage.keys()).isEqualTo(keys);
        assertThat(attachments(MAIN)).isEqualTo(posts);
        assertThat(mediaRows()).isEqualTo(rows);
        assertThat(recorder.stored).hasSize(announced);
    }

    private void assertRefused(Upload.Builder upload, String code, String message) throws Exception {
        assertNothingWritten(() -> asCarol(upload), MediaException.class, code, message);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void aJpegUploadStoresTheFileAndTheAttachment() throws Exception {
        byte[] jpegBytes = MediaFixtures.jpeg(64, 48);
        MediaSnapshot m = asCarol(upload("IMG_1234.JPG", jpegBytes, "image/jpeg"));
        assertThat(m.storageKey()).isEqualTo("2026/11/IMG_1234.jpg");
        assertThat(m.attachedFile()).isEqualTo("2026/11/IMG_1234.jpg");
        assertThat(m.mimeType()).isEqualTo("image/jpeg");
        assertThat(m.post().type()).isEqualTo("attachment");
        assertThat(m.post().title()).isEqualTo("IMG_1234");
        assertThat(m.post().slug()).isEqualTo("img_1234");
        assertThat(m.post())
                .extracting(
                        PostSnapshot::status,
                        PostSnapshot::commentStatus,
                        PostSnapshot::pingStatus,
                        PostSnapshot::excerpt,
                        PostSnapshot::content)
                .containsExactly("inherit", "open", "closed", "", "");
        assertThat(m.post().author()).contains(carol);
        assertThat(m.post().parent()).isEmpty();
        assertThat(m.post().localDate(ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(m.post().guid()).isEqualTo(m.url()).isEqualTo("http://localhost/test-uploads/2026/11/IMG_1234.jpg");
        assertThat(m.url("full")).isEqualTo(m.url());
        assertThat(m.processing()).isEqualTo(Processing.PENDING);
        assertThat(m.wordPressMetadata()).isEmpty();
        assertThat(m.fileSize()).isEqualTo(jpegBytes.length);
        assertThat(m.width()).hasValue(64);
        assertThat(m.height()).hasValue(48);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.imageMeta())
                .hasValueSatisfying(
                        meta -> assertThat(meta.get("title").textValue()).isEmpty());
        assertThat(m.altText()).isEmpty();
        assertThat(storage.read("2026/11/IMG_1234.jpg")).isEqualTo(jpegBytes);
        assertThat(storage.keys()).containsExactly("2026/11/IMG_1234.jpg");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(storage_key, ' ', mime_type, ' ', file_size, ' ', stored_bytes, ' ', processing)"
                                + " from qp_media where post_id = ?",
                        m.post().id().value()))
                .containsExactly(
                        "2026/11/IMG_1234.jpg image/jpeg " + jpegBytes.length + " " + jpegBytes.length + " pending");
        assertThat(as(Identities.user(carol), () -> media.find(m.post().id()))).contains(m);
        // a post that is not an attachment is no media
        PostSnapshot post = as(
                Identities.user(bob),
                () -> posts.create(PostChanges.builder()
                        .title("Not media")
                        .status("publish")
                        .build()));
        assertThat(as(Identities.user(carol), () -> media.find(post.id()))).isEmpty();
    }

    @Test
    void titlesFallBackAsWordPressDoes() throws Exception {
        assertThat(store(cp(0xC9) + "t" + cp(0xE9) + " " + cp(0xE0) + " Paris (2).JPG"))
                .extracting(t -> t.post().title(), MediaSnapshot::storageKey)
                .containsExactly(
                        cp(0xC9) + "t" + cp(0xE9) + " " + cp(0xE0) + " Paris (2)", "2026/11/Ete-a-Paris-2.jpg");
        assertThat(storeText("report.v2.txt").post().title()).isEqualTo("report.v2");
        assertThat(storeText(".txt"))
                .extracting(t -> t.post().title(), MediaSnapshot::storageKey)
                .containsExactly("unnamed-file", "2026/11/unnamed-file.txt");
        assertThat(store(
                        jpeg(JpegMeta.none()
                                .withIptc(105, "Sunset at S" + cp(0xE8) + "te")
                                .withIptc(120, "Taken from the pier, 2025")),
                        "x.jpg"))
                .extracting(
                        t -> t.post().title(),
                        t -> t.post().excerpt(),
                        t -> t.post().content())
                .containsExactly("Sunset at S" + cp(0xE8) + "te", "Taken from the pier, 2025", "");
        assertThat(store(jpeg(JpegMeta.none().withIptc(5, "20250612")), "holiday.jpg")
                        .post()
                        .title())
                .isEqualTo("holiday");
        // the client name as sent, before the content corrected it: a PNG named .jpg is stored as .png
        assertThat(asCarol(upload("drawing.jpg", MediaFixtures.png(8, 8), "application/octet-stream")))
                .extracting(t -> t.post().title(), MediaSnapshot::storageKey, MediaSnapshot::mimeType)
                .containsExactly("drawing", "2026/11/drawing.png", "image/png");
    }

    @Test
    void fileAndSlugNumbersAreIndependent() throws Exception {
        List<MediaSnapshot> stored = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stored.add(store("editor-har-large.jpg"));
        }
        assertThat(stored)
                .extracting(MediaSnapshot::storageKey)
                .containsExactly(
                        "2026/11/editor-har-large.jpg",
                        "2026/11/editor-har-large-1.jpg",
                        "2026/11/editor-har-large-2.jpg");
        assertThat(stored)
                .extracting(m -> m.post().slug())
                .containsExactly("editor-har-large", "editor-har-large-2", "editor-har-large-3");
        assertThat(stored).extracting(m -> m.post().title()).containsOnly("editor-har-large");
    }

    @Test
    void requestFieldsWin() throws Exception {
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<Iptc4xmpCore:AltTextAccessibility><rdf:Alt><rdf:li xml:lang=\"x-default\">A red kite</rdf:li>"
                + "</rdf:Alt></Iptc4xmpCore:AltTextAccessibility></rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] embedded = jpeg(JpegMeta.none()
                .withIptc(105, "Embedded title")
                .withIptc(120, "Embedded caption")
                .withXmp(xmp));
        MediaSnapshot given = asCarol(upload("kite.jpg", embedded, "image/jpeg")
                .title("T")
                .caption("C")
                .description("D")
                .altText(" <b>A</b>  kite "));
        assertThat(given.post())
                .extracting(PostSnapshot::title, PostSnapshot::excerpt, PostSnapshot::content)
                .containsExactly("T", "C", "D");
        assertThat(given.altText()).isEqualTo("A kite");
        assertThat(asCarol(upload("kite.jpg", embedded, "image/jpeg").altText(""))
                        .altText())
                .isEmpty();
        MediaSnapshot fallback =
                asCarol(upload("kite.jpg", embedded, "image/jpeg").title("").caption(""));
        assertThat(fallback.post())
                .extracting(PostSnapshot::title, PostSnapshot::excerpt, PostSnapshot::content)
                .containsExactly("Embedded title", "Embedded caption", "");
        assertThat(fallback.altText()).isEqualTo("A red kite");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select alt_text from qp_media where post_id = ?",
                        fallback.post().id().value()))
                .containsExactly("A red kite");
    }

    @Test
    void aZeroTitleOrCaptionCountsAsEmptyAsInPhp() throws Exception {
        byte[] captioned = jpeg(JpegMeta.none().withIptc(120, "Embedded caption"));
        MediaSnapshot zeroTitle = asCarol(
                upload("zero.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").title("0"));
        assertThat(zeroTitle.post().title()).isEqualTo("zero");
        MediaSnapshot zeroCaption =
                asCarol(upload("c0.jpg", captioned, "image/jpeg").caption("0"));
        assertThat(zeroCaption.post().excerpt()).isEqualTo("Embedded caption");
        // with nothing embedded the request's "0" is kept
        assertThat(asCarol(upload("c1.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .caption("0"))
                        .post()
                        .excerpt())
                .isEqualTo("0");
    }

    @Test
    void theRequestsAuthorStatusAndFormatChoiceAreUsed() throws Exception {
        MediaSnapshot forCarol = as(
                bob,
                upload("for-carol.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .author(carol)
                        .status("private"));
        assertThat(forCarol.post().author()).contains(carol);
        assertThat(forCarol.post().status()).isEqualTo("private");
        // carol naming herself needs nothing more
        assertThat(asCarol(upload("self.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .author(carol))
                        .post()
                        .author())
                .contains(carol);
        // a HEIC beside a JPEG of its name takes another number unless the upload keeps its format
        asCarol(upload("trip.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"));
        assertThat(asCarol(upload("trip.heic", MediaFixtures.heif("heic"), "image/heic"))
                        .storageKey())
                .isEqualTo("2026/11/trip-1.heic");
        assertThat(asCarol(upload("trip.heic", MediaFixtures.heif("heic"), "image/heic")
                                .convertFormat(false))
                        .storageKey())
                .isEqualTo("2026/11/trip.heic");
        // a still HEIF declared as such is let through whatever the processor supports
        assertThat(asCarol(upload("still.heic", MediaFixtures.heif("heif"), "image/heif"))
                        .mimeType())
                .isEqualTo("image/heic");
    }

    @Test
    void theAltTextFollowsTheSitesLanguage() throws Exception {
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<Iptc4xmpCore:AltTextAccessibility><rdf:Alt><rdf:li xml:lang=\"x-default\">A kite</rdf:li>"
                + "<rdf:li xml:lang=\"de\">Ein Drachen</rdf:li></rdf:Alt></Iptc4xmpCore:AltTextAccessibility>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] bytes = jpeg(JpegMeta.none().withXmp(xmp));
        assertThat(asCarol(upload("en.jpg", bytes, "image/jpeg")).altText()).isEqualTo("A kite");
        TestData.siteOption(dataSource, MAIN, "WPLANG", "\"de_DE\"");
        assertThat(asCarol(upload("de.jpg", bytes, "image/jpeg")).altText()).isEqualTo("Ein Drachen");
    }

    @Test
    void theGuidMayFillItsColumnExactly() throws Exception {
        String prefix = "http://localhost/test-uploads/2026/11/";
        String fits = "f".repeat(255 - prefix.length() - ".jpg".length()) + ".jpg";
        MediaSnapshot stored = asCarol(upload(fits, MediaFixtures.jpeg(8, 8), "image/jpeg"));
        assertThat(stored.post().guid()).hasSize(255).isEqualTo(prefix + fits);
        assertRefused(upload("g" + fits, MediaFixtures.jpeg(8, 8), "image/jpeg"), "upload_error", MOVE_REFUSED);
    }

    @Test
    void aSignedServedUrlLongerThanAGuidIsStoredUnderItsStableGuid() throws Exception {
        storage.signUrls(true);
        MediaSnapshot stored = asCarol(upload("signed.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"));
        String guid = "http://localhost/test-uploads/2026/11/signed.jpg";
        assertThat(stored.post().guid()).isEqualTo(guid);
        assertThat(stored.url()).startsWith(guid + "?signature=").hasSizeGreaterThan(255);
    }

    @Test
    void anUploadWhoseSizeIsNotItsFilesIsRefused() throws Exception {
        Upload built = upload("sized.txt", MediaFixtures.text("twelve bytes"), "text/plain")
                .build();
        Upload lying = new Upload(
                built.clientName(),
                built.declaredType(),
                built.file(),
                1,
                built.parent(),
                built.title(),
                built.caption(),
                built.description(),
                built.altText(),
                built.status(),
                built.author(),
                built.generateSubSizes(),
                built.convertFormat(),
                built.date(),
                built.dateGmt(),
                built.slug(),
                built.commentStatus(),
                built.pingStatus(),
                built.imageTypeCheck(),
                built.clientNameTitle(),
                built.sideloaded());
        assertNothingWritten(
                () -> as(Identities.user(carol), () -> media.store(lying)), IllegalArgumentException.class, null, null);
    }

    @Test
    void refusalsComeBeforeAnyWrite() throws Exception {
        PostSnapshot bobsPost = as(
                Identities.user(bob),
                () -> posts.create(PostChanges.builder()
                        .title("Bob's post")
                        .status("publish")
                        .build()));
        MediaSnapshot attachment = as(bob, upload("bob.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"));
        PostId revision = TestData.post(
                dataSource, MAIN, "revision", "inherit", bobsPost.id().value() + "-revision-v1", "R");
        Jdbc.update(
                dataSource,
                "update qp_post set parent_id = ? where id = ?",
                bobsPost.id().value(),
                revision.value());
        SiteId other = TestData.site(dataSource);
        PostId otherSitesPost = TestData.post(dataSource, other, "post", "publish", "elsewhere", "Elsewhere");

        assertNothingWritten(
                () -> as(dave, upload("d.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")),
                ForbiddenException.class,
                null,
                null);
        assertNothingWritten(
                () -> as(
                        Identities.anonymous(),
                        () -> media.store(upload("e.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .build())),
                UnauthorizedException.class,
                null,
                null);
        assertNothingWritten(
                () -> as(
                        carol,
                        upload("c.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").parent(bobsPost.id())),
                ForbiddenException.class,
                null,
                null);
        assertNothingWritten(
                () -> as(
                        bob,
                        upload("b.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .parent(attachment.post().id())),
                MediaException.class,
                "rest_invalid_param",
                "Invalid parent type.");
        assertNothingWritten(
                () -> as(
                        bob,
                        upload("b.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").parent(revision)),
                MediaException.class,
                "rest_invalid_param",
                "Invalid parent type.");
        for (PostId missing : List.of(otherSitesPost, PostId.of(999_999_999L))) {
            assertNothingWritten(
                    () -> as(
                            bob,
                            upload("b.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                    .parent(missing)),
                    MediaException.class,
                    "rest_cannot_edit",
                    "Sorry, you are not allowed to upload media to this post.");
        }
        // an author other than the caller needs edit_others_posts, checked before anything is stored
        assertNothingWritten(
                () -> as(
                        carol,
                        upload("a.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg").author(bob)),
                ForbiddenException.class,
                null,
                null);
        // the parent's edit check and a missing parent both come before the unsupported image
        assertNothingWritten(
                () -> as(
                        carol,
                        upload("w.webp", MediaFixtures.webp(), "image/webp").parent(bobsPost.id())),
                ForbiddenException.class,
                null,
                null);
        assertNothingWritten(
                () -> as(
                        bob,
                        upload("w.webp", MediaFixtures.webp(), "image/webp").parent(PostId.of(999_999_999L))),
                MediaException.class,
                "rest_cannot_edit",
                "Sorry, you are not allowed to upload media to this post.");
        // the unsupported image is refused before the parent's type is looked at
        assertNothingWritten(
                () -> as(
                        bob,
                        upload("w.webp", MediaFixtures.webp(), "image/webp")
                                .parent(attachment.post().id())),
                MediaException.class,
                "rest_upload_image_type_not_supported",
                UNSUPPORTED);
        // the caller's transaction stays usable after a refusal
        assertThat(as(Identities.user(bob), () -> {
                    try {
                        media.store(upload("b.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .parent(attachment.post().id())
                                .build());
                    } catch (MediaException expected) {
                        // refused
                    }
                    return transactions.getStatus();
                }))
                .isEqualTo(Status.STATUS_ACTIVE);
        // a parent bob may edit is accepted
        assertThat(as(
                                bob,
                                upload("b.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                        .parent(bobsPost.id()))
                        .post()
                        .parent())
                .contains(bobsPost.id());
    }

    @Test
    void imagesTheProcessorCannotMakeSizesForAreRefused() throws Exception {
        assertRefused(
                upload("x.webp", MediaFixtures.webp(), "image/webp"),
                "rest_upload_image_type_not_supported",
                UNSUPPORTED);
        assertRefused(
                upload("x.jpg", MediaFixtures.jpeg(8, 8), "image/jpg"),
                "rest_upload_image_type_not_supported",
                UNSUPPORTED);
        assertRefused(
                upload("x.heics", MediaFixtures.heif("heic"), "image/heic-sequence"),
                "rest_upload_image_type_not_supported",
                UNSUPPORTED);
        assertRefused(
                upload("x.bmp", MediaFixtures.bmp(8, 8), "image/bmp"),
                "rest_upload_image_type_not_supported",
                UNSUPPORTED);
        assertThat(asCarol(upload("x.webp", MediaFixtures.webp(), "image/webp").generateSubSizes(false)))
                .extracting(MediaSnapshot::storageKey, MediaSnapshot::mimeType)
                .containsExactly("2026/11/x.webp", "image/webp");
        assertThat(asCarol(upload("x.jpg", MediaFixtures.jpeg(8, 8), "application/octet-stream"))
                        .mimeType())
                .isEqualTo("image/jpeg");
        assertThat(asCarol(upload("y.jpg", MediaFixtures.jpeg(8, 8))).mimeType())
                .isEqualTo("image/jpeg");
        MediaSnapshot heic = asCarol(upload("x.heic", MediaFixtures.heif("heic"), "image/heic"));
        assertThat(heic.mimeType()).isEqualTo("image/heic");
        // without readable dimensions there is no image_meta
        assertThat(heic.width()).isEmpty();
        assertThat(heic.imageMeta()).isEmpty();
        assertRefused(
                upload("x.svg", MediaFixtures.text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "image/svg+xml"),
                "upload_error",
                NOT_ALLOWED);
    }

    @Test
    void lyingContentNeverReachesStorage() throws Exception {
        assertRefused(
                upload("photo.jpg", MediaFixtures.text("<?php system($_GET['c']); ?>"), "image/jpeg"),
                "upload_error",
                NOT_ALLOWED);
        assertRefused(
                upload("x.png", MediaFixtures.text("<html><script>x</script></html>"), "image/png"),
                "upload_error",
                NOT_ALLOWED);
        assertRefused(
                upload("x.svg", MediaFixtures.text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "image/svg+xml"),
                "upload_error",
                NOT_ALLOWED);
        assertRefused(upload("x.jpg", MediaFixtures.pdf(), "image/jpeg"), "upload_error", NOT_ALLOWED);
        assertRefused(upload("x.jpg", MediaFixtures.pdf(), "application/pdf"), "upload_error", NOT_ALLOWED);
        assertThat(storage.keys()).isEmpty();
        java.io.ByteArrayOutputStream polyglot = new java.io.ByteArrayOutputStream();
        polyglot.writeBytes(MediaFixtures.gif(4, 4, 1));
        polyglot.writeBytes("\n;alert(document.domain);//\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(asCarol(upload("x.gif", polyglot.toByteArray(), "image/gif")))
                .extracting(MediaSnapshot::storageKey, MediaSnapshot::mimeType)
                .containsExactly("2026/11/x.gif", "image/gif");
        assertThat(asCarol(upload("shell.php.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")))
                .extracting(MediaSnapshot::storageKey, MediaSnapshot::mimeType)
                .containsExactly("2026/11/shell.php_.jpg", "image/jpeg");
        assertThat(storage.keys()).containsExactly("2026/11/shell.php_.jpg", "2026/11/x.gif");
    }

    @Test
    void emptyFilesAreRefused() throws Exception {
        assertRefused(
                upload("x.txt", new byte[0], "text/plain"),
                "upload_error",
                "File is empty. Please upload something more substantial. This error could also be caused by uploads"
                        + " being disabled in your php.ini file or by post_max_size being defined as smaller than"
                        + " upload_max_filesize in php.ini.");
    }

    @Test
    void theConfiguredMaximumSizeAndUploadFilters() throws Exception {
        assertRefused(
                upload("big.txt", MediaFixtures.text("x".repeat(64 * 1024 + 1)), "text/plain"),
                "upload_error",
                "This file is too big. Files must be less than 64 KB in size.");
        // the size limit comes before the upload filters
        assertRefused(
                upload("blocked.txt", MediaFixtures.text("x".repeat(64 * 1024 + 1)), "text/plain"),
                "upload_error",
                "This file is too big. Files must be less than 64 KB in size.");
        // exactly the limit passes
        assertThat(asCarol(upload("limit.txt", MediaFixtures.text("x".repeat(64 * 1024)), "text/plain"))
                        .fileSize())
                .isEqualTo(64 * 1024);
        assertRefused(
                upload("blocked.txt", MediaFixtures.text("text\n"), "text/plain"),
                "upload_error",
                "Blocked by policy.");
        UploadCheck seen = recorder.checks.get(recorder.checks.size() - 1);
        assertThat(seen.site()).isEqualTo(MAIN);
        assertThat(seen.user()).contains(carol);
        assertThat(seen.clientName()).isEqualTo("blocked.txt");
        assertThat(seen.declaredType()).contains("text/plain");
        assertThat(seen.size()).isEqualTo(5);
        // the filters run before the empty-file check
        assertRefused(upload("blocked.txt", new byte[0], "text/plain"), "upload_error", "Blocked by policy.");
    }

    @Test
    void concurrentUploadsOfOneNameGetTwoFiles() throws Exception {
        byte[] carols = MediaFixtures.jpeg(10, 10);
        byte[] bobs = MediaFixtures.jpeg(12, 12);
        List<Object> results = Races.run(List.of(
                () -> as(carol, upload("photo.jpg", carols, "image/jpeg")),
                () -> as(bob, upload("photo.jpg", bobs, "image/jpeg"))));
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(MediaSnapshot.class));
        MediaSnapshot fromCarol = (MediaSnapshot) results.get(0);
        MediaSnapshot fromBob = (MediaSnapshot) results.get(1);
        assertThat(List.of(fromCarol.storageKey(), fromBob.storageKey()))
                .containsExactlyInAnyOrder("2026/11/photo.jpg", "2026/11/photo-1.jpg");
        assertThat(storage.read(fromCarol.storageKey())).isEqualTo(carols);
        assertThat(storage.read(fromBob.storageKey())).isEqualTo(bobs);
        assertThat(fromCarol.post().id()).isNotEqualTo(fromBob.post().id());
        assertThat(Jdbc.strings(
                        dataSource,
                        "select storage_key from qp_media where post_id in (?, ?) order by storage_key",
                        fromCarol.post().id().value(),
                        fromBob.post().id().value()))
                .containsExactly("2026/11/photo-1.jpg", "2026/11/photo.jpg");

        // a file that appears between the listing and the write is never replaced: another name is chosen
        byte[] other = MediaFixtures.text("another upload\n");
        storage.appearBeforeWrite("2026/11/race.jpg", other);
        assertThat(store("race.jpg").storageKey()).isEqualTo("2026/11/race-1.jpg");
        assertThat(storage.read("2026/11/race.jpg")).isEqualTo(other);
        // ten names taken in a row: the upload gives up
        storage.takeEveryName(9);
        assertThat(store("busy.jpg").storageKey()).isEqualTo("2026/11/busy-9.jpg");
        storage.takeEveryName(10);
        long posts = attachments(MAIN);
        assertThatThrownBy(() -> store("busier.jpg"))
                .isInstanceOfSatisfying(
                        MediaException.class, e -> assertThat(e.code()).isEqualTo("upload_error"))
                .hasMessage(MOVE_REFUSED);
        assertThat(attachments(MAIN)).isEqualTo(posts);
        assertThat(storage.read("2026/11/busier-9.jpg"))
                .isEqualTo("another upload".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void aFailedInsertRemovesTheStoredFile() throws Exception {
        long posts = attachments(MAIN);
        assertThatThrownBy(() -> asCarol(upload("boom.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .title("boom")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("boom"));
        assertThat(storage.list("2026/11")).isEmpty();
        assertThat(attachments(MAIN)).isEqualTo(posts);
        assertThat(recorder.stored).isEmpty();
        // inside the caller's transaction: the failure marks it for rollback, and the file goes with the rollback
        AtomicInteger statusAfterFailure = new AtomicInteger(-1);
        AtomicReference<List<String>> keysBeforeRollback = new AtomicReference<>();
        Throwable commitFailure =
                org.assertj.core.api.Assertions.catchThrowable(() -> as(Identities.user(carol), () -> {
                    try {
                        media.store(upload("boom.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .title("boom")
                                .build());
                    } catch (PostException expected) {
                        statusAfterFailure.set(transactions.getStatus());
                        keysBeforeRollback.set(storage.keys());
                    }
                    return null;
                }));
        assertThat(statusAfterFailure.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(keysBeforeRollback.get()).containsExactly("2026/11/boom.jpg");
        // the caller's commit fails because the transaction was marked, not because of the test
        assertThat(commitFailure).isNotNull().isNotInstanceOf(AssertionError.class);
        assertThat(Failures.messages(commitFailure)).contains("RollbackException");
        assertThat(storage.keys()).isEmpty();
        assertThat(attachments(MAIN)).isEqualTo(posts);
        // without a caller's transaction the upload's own transaction rolls back and removes the file itself
        AtomicReference<List<String>> keysAfterOwnTransaction = new AtomicReference<>();
        AtomicReference<Throwable> ownFailure = new AtomicReference<>();
        as(Identities.user(carol), () -> {
            QuarkusTransaction.suspendingExisting().run(() -> {
                try {
                    media.store(upload("boom.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                            .title("boom")
                            .build());
                } catch (RuntimeException failure) {
                    ownFailure.set(failure);
                }
                keysAfterOwnTransaction.set(storage.keys());
            });
            return null;
        });
        assertThat(ownFailure.get()).isInstanceOf(PostException.class);
        assertThat(keysAfterOwnTransaction.get()).isEmpty();
        assertThat(attachments(MAIN)).isEqualTo(posts);
    }

    @Test
    void aRollbackOnlyCallerTransactionLeavesNoFile() throws Exception {
        long before = attachments(MAIN);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Throwable outer = org.assertj.core.api.Assertions.catchThrowable(() -> as(Identities.user(carol), () -> {
            transactions.setRollbackOnly();
            try {
                media.store(upload("doomed.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .build());
            } catch (RuntimeException refused) {
                failure.set(refused);
            }
            return null;
        }));
        assertThat(outer).isNotInstanceOf(AssertionError.class);
        assertThat(storage.keys()).isEmpty();
        assertThat(attachments(MAIN)).as("%s", failure.get()).isEqualTo(before);
    }

    @Test
    void aNameTooLongForTheFilesystemIsRefusedAsWordPressRefusesTheMove() throws Exception {
        // a 300-character name makes a URL longer than the guid column
        assertRefused(
                upload("a".repeat(296) + ".jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"), "upload_error", MOVE_REFUSED);
        // 130 two-byte characters fit the guid but not a filesystem name of at most 255 bytes
        assertRefused(
                upload(cp(0x416).repeat(130) + ".jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"),
                "upload_error",
                MOVE_REFUSED);
        // a folder that cannot be read or made
        storage.listingFails(true);
        assertRefused(
                upload("photo.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg"),
                "upload_error",
                "Unable to create directory wp-content/uploads/2026/11. Is its parent directory writable by the server?");
    }

    @Test
    void aGuidLongerThanThePostColumnIsRefusedBeforeStoring() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "UTC");
        TestData.member(dataSource, site, carol, "[\"author\"]");
        long posts = attachments(site);
        String name = "n".repeat(236) + ".jpg";
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(carol),
                        () -> media.store(upload(name, MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .build())))
                .isInstanceOfSatisfying(
                        MediaException.class, e -> assertThat(e.code()).isEqualTo("upload_error"))
                .hasMessage("The uploaded file could not be moved to wp-content/uploads/sites/" + site.value()
                        + "/2026/11.");
        assertThat(storage.keys()).isEmpty();
        assertThat(attachments(site)).isEqualTo(posts);
        // a shorter name on the same site is stored under the site's folder
        MediaSnapshot stored = Identities.onSite(
                site,
                Identities.user(carol),
                () -> media.store(upload("short.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .build()));
        assertThat(stored.storageKey()).isEqualTo("sites/" + site.value() + "/2026/11/short.jpg");
        assertThat(stored.post().guid().codePointCount(0, stored.post().guid().length()))
                .isLessThanOrEqualTo(255);
    }

    @Test
    void storingAnnouncesMediaStoredOnce() throws Exception {
        MediaSnapshot m = store("announced.jpg");
        assertThat(recorder.stored).hasSize(1);
        MediaStored event = recorder.stored.get(0);
        assertThat(event.site()).isEqualTo(MAIN);
        assertThat(event.at()).isEqualTo(NOW);
        assertThat(event.media()).isEqualTo(m);
        assertThat(recorder.rowsSeenAfterCommit
                        .computeIfAbsent(m.post().id().value(), key -> new CompletableFuture<>())
                        .get(10, TimeUnit.SECONDS))
                .isEqualTo(1);
        assertRefused(upload("x.txt", new byte[0], "text/plain"), "upload_error", null);
        assertThat(recorder.stored).hasSize(1);
        // an insert that fails before the announcement announces nothing
        assertThatThrownBy(() -> asCarol(upload("boom.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .title("boom")))
                .isInstanceOf(PostException.class);
        assertThat(recorder.stored).hasSize(1);
        // a synchronous observer that refuses rolls the upload back: it was told, asynchronous observers never are
        assertThatThrownBy(() -> asCarol(upload("late.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                        .title("late boom")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recorder.stored).hasSize(2);
        long rolledBack = recorder.stored.get(1).media().post().id().value();
        assertThat(storage.keys()).doesNotContain("2026/11/late.jpg");
        MediaSnapshot after = store("after.jpg");
        assertThat(recorder.rowsSeenAfterCommit
                        .computeIfAbsent(after.post().id().value(), key -> new CompletableFuture<>())
                        .get(10, TimeUnit.SECONDS))
                .isEqualTo(1);
        Thread.sleep(500);
        assertThat(recorder.rowsSeenAfterCommit).doesNotContainKey(rolledBack);
    }

    @Test
    void theMediaFeatureOffRefuses() throws Exception {
        SiteId site = TestData.siteIn(dataSource, "UTC");
        TestData.member(dataSource, site, carol, "[\"author\"]");
        TestData.siteOption(dataSource, site, "qp_features", "{\"media\": false}");
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(carol),
                        () -> media.store(upload("off.jpg", MediaFixtures.jpeg(8, 8), "image/jpeg")
                                .build())))
                .isInstanceOfSatisfying(
                        SiteFeatureDisabledException.class,
                        e -> assertThat(e.key()).isEqualTo("media"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(carol), () -> media.find(PostId.of(1))))
                .isInstanceOf(SiteFeatureDisabledException.class);
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void theWordPressMetadataTakesWordPresssShapeOnceProcessed() throws Exception {
        MediaSnapshot image = store("shape.jpg");
        ObjectNode meta = image.imageMeta().orElseThrow();
        ObjectNode extra = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("k", "v");
        MediaSnapshot processed = new MediaSnapshot(
                image.post(),
                image.mimeType(),
                image.storageKey(),
                image.attachedFile(),
                Optional.of("shape-orig.jpg"),
                image.fileSize(),
                image.width(),
                image.height(),
                Map.of("thumbnail", new org.quarkpress.media.MediaSize("shape-4x4.jpg", 4, 4, "image/jpeg", 99)),
                Optional.of(meta),
                extra,
                "",
                Processing.DONE,
                image.url(),
                Map.of("thumbnail", "http://localhost/test-uploads/2026/11/shape-4x4.jpg"));
        ObjectNode wp = processed.wordPressMetadata();
        assertThat(wp.fieldNames())
                .toIterable()
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta", "original_image", "k");
        assertThat(wp.get("file").textValue()).isEqualTo("2026/11/shape.jpg");
        assertThat(wp.at("/sizes/thumbnail").toString())
                .isEqualTo("{\"file\":\"shape-4x4.jpg\",\"width\":4,\"height\":4,\"mime-type\":\"image/jpeg\","
                        + "\"filesize\":99}");
        assertThat(processed.url("thumbnail")).isEqualTo("http://localhost/test-uploads/2026/11/shape-4x4.jpg");
        assertThatThrownBy(() -> processed.url("medium")).isInstanceOf(IllegalArgumentException.class);
        MediaSnapshot text = storeText("notes.txt");
        MediaSnapshot doneText = new MediaSnapshot(
                text.post(),
                text.mimeType(),
                text.storageKey(),
                text.attachedFile(),
                Optional.empty(),
                text.fileSize(),
                text.width(),
                text.height(),
                Map.of(),
                Optional.empty(),
                extra,
                "",
                Processing.NONE,
                text.url(),
                Map.of());
        assertThat(doneText.wordPressMetadata().toString()).isEqualTo("{\"k\":\"v\",\"filesize\":10}");
    }
}
