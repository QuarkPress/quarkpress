package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaProcessed;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * Processing an upload as WordPress's server path does: the main-file copy (scaled, rotated), the sizes in their
 * order and with their names, the metadata saved first and after each size, the files the processor cannot handle,
 * audio and video metadata, the limits on pixels and on concurrent decodes, failures that leave the attachment
 * partial and the post-process that completes it.
 */
class MediaProcessDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    MediaProcessDbTest.class, TestStorage.class, TestProcessor.class, Recorder.class)
            .overrideConfigKey("quarkpress.media.accept-unprocessable-images", "true")
            .overrideConfigKey("quarkpress.media.max-concurrent-decodes", "1");

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final List<String> SIX = List.of("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
    static final String REFUSED_IN_TRANSACTION = "Media.upload and Media.process must be called outside a"
            + " transaction: processing commits each save on its own";

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

    @Inject
    Recorder recorder;

    @Inject
    Posts posts;

    UserId carol;
    UserId bob;
    UserId erin;

    /** Records the announcements of processing runs (no client proxy). */
    @Singleton
    public static class Recorder {
        final List<MediaProcessed> processed = new CopyOnWriteArrayList<>();

        void processed(@Observes MediaProcessed event) {
            processed.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        recorder.processed.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
        bob = TestData.memberOfMain(dataSource, "editor");
        erin = TestData.memberOfMain(dataSource, "subscriber");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-process"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String declared(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "avif" -> "image/avif";
            case "tif" -> "image/tiff";
            case "pdf" -> "application/pdf";
            case "mp4" -> "video/mp4";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "webm" -> "video/webm";
            case "ogg" -> "audio/ogg";
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Upload.Builder uploadOf(String name, byte[] bytes) {
        return Upload.builder(name, file(bytes)).declaredType(declared(name));
    }

    /**
     * Runs {@code work} on the main site as {@code user}, outside any transaction; what it throws is rethrown as it is
     * (the transaction runner wraps it).
     */
    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(user), () -> {
            try {
                return QuarkusTransaction.suspendingExisting().call(work);
            } catch (QuarkusTransactionException wrapped) {
                throw wrapped.getCause() instanceof Exception cause ? cause : wrapped;
            }
        });
    }

    private MediaSnapshot upload(Upload.Builder upload) throws Exception {
        return as(carol, () -> media.upload(upload.build()));
    }

    private MediaSnapshot upload(String name, byte[] bytes) throws Exception {
        return upload(uploadOf(name, bytes));
    }

    private MediaSnapshot process(PostId id) throws Exception {
        return as(carol, () -> media.process(id));
    }

    private List<String> missing(PostId id) throws Exception {
        return as(carol, () -> media.missingSizes(id));
    }

    private MediaSnapshot find(PostId id) throws Exception {
        return as(carol, () -> media.find(id)).orElseThrow();
    }

    private static byte[] jpeg(int w, int h) {
        return MediaFixtures.jpeg(w, h);
    }

    private static byte[] jpeg(int w, int h, int orientation) {
        return MediaFixtures.jpeg(w, h, JpegMeta.none().withOrientation(orientation));
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> files(MediaSnapshot m) {
        return m.sizes().values().stream().map(MediaSize::file).toList();
    }

    private long storedBytes(MediaSnapshot m) {
        return Jdbc.count(
                dataSource,
                "select stored_bytes from qp_media where post_id = ?",
                m.post().id().value());
    }

    private long version(MediaSnapshot m) {
        return Jdbc.count(
                dataSource,
                "select version from qp_media where post_id = ?",
                m.post().id().value());
    }

    private String folderKey(String name) {
        return "2026/11/" + name;
    }

    /** The number of frames of the GIF stored under {@code key}. */
    private int frames(String key) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(storage.read(key)))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in, false);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    // ---- the server path -------------------------------------------------------------------------------------------

    @Test
    void aLargeJpegIsScaledAndGetsEverySize() throws Exception {
        MediaSnapshot m = upload("IMG_1234.jpg", jpeg(4000, 3000));
        assertThat(m.storageKey()).isEqualTo("2026/11/IMG_1234-scaled.jpg");
        assertThat(m.attachedFile()).isEqualTo("2026/11/IMG_1234-scaled.jpg");
        assertThat(m.width()).hasValue(2560);
        assertThat(m.height()).hasValue(1920);
        assertThat(m.originalImage()).contains("IMG_1234.jpg");
        assertThat(m.mimeType()).isEqualTo("image/jpeg");
        assertThat(m.sizes().keySet())
                .containsExactly("medium", "large", "thumbnail", "medium_large", "1536x1536", "2048x2048");
        assertThat(files(m))
                .containsExactly(
                        "IMG_1234-300x225.jpg",
                        "IMG_1234-1024x768.jpg",
                        "IMG_1234-150x150.jpg",
                        "IMG_1234-768x576.jpg",
                        "IMG_1234-1536x1152.jpg",
                        "IMG_1234-2048x1536.jpg");
        assertThat(m.sizes().values())
                .extracting(MediaSize::width, MediaSize::height)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300, 225),
                        org.assertj.core.groups.Tuple.tuple(1024, 768),
                        org.assertj.core.groups.Tuple.tuple(150, 150),
                        org.assertj.core.groups.Tuple.tuple(768, 576),
                        org.assertj.core.groups.Tuple.tuple(1536, 1152),
                        org.assertj.core.groups.Tuple.tuple(2048, 1536));
        assertThat(m.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/jpeg");
        assertThat(fieldNames(m.wordPressMetadata()))
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta", "original_image");
        assertThat(m.wordPressMetadata().get("file").textValue()).isEqualTo("2026/11/IMG_1234-scaled.jpg");
        assertThat(m.wordPressMetadata().get("original_image").textValue()).isEqualTo("IMG_1234.jpg");
        assertThat(fieldNames((ObjectNode) m.wordPressMetadata().get("sizes").get("medium")))
                .containsExactly("file", "width", "height", "mime-type", "filesize");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        // every size's filesize is its stored file's, and stored_bytes counts all eight files
        long total = 0;
        for (MediaSize size : m.sizes().values()) {
            assertThat(storage.size(folderKey(size.file()))).hasValue(size.fileSize());
        }
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storage.keys()).hasSize(8).contains("2026/11/IMG_1234.jpg", "2026/11/IMG_1234-scaled.jpg");
        assertThat(m.fileSize())
                .isEqualTo(storage.size("2026/11/IMG_1234-scaled.jpg").orElseThrow());
        assertThat(storedBytes(m)).isEqualTo(total);
        assertThat(find(m.post().id())).isEqualTo(m);
        assertThat(missing(m.post().id())).isEmpty();
        // the original is decoded once for the main file and every size
        assertThat(processor.decodes()).isEqualTo(1);
    }

    @Test
    void anOrientedJpegGetsARotatedMainFile() throws Exception {
        MediaSnapshot m = upload("photo.jpg", jpeg(1000, 800, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/photo-rotated.jpg");
        assertThat(m.width()).hasValue(800);
        assertThat(m.height()).hasValue(1000);
        assertThat(m.originalImage()).contains("photo.jpg");
        assertThat(m.imageMeta().orElseThrow().get("orientation")).isEqualTo(IntNode.valueOf(1));
        assertThat(files(m)).containsExactly("photo-240x300.jpg", "photo-150x150.jpg", "photo-768x960.jpg");
        assertThat(m.sizes().keySet()).containsExactly("medium", "thumbnail", "medium_large");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(storage.keys())
                .containsExactlyInAnyOrder(
                        "2026/11/photo.jpg",
                        "2026/11/photo-rotated.jpg",
                        "2026/11/photo-240x300.jpg",
                        "2026/11/photo-150x150.jpg",
                        "2026/11/photo-768x960.jpg");
    }

    @Test
    void scaledAndRotatedMakeOneFile() throws Exception {
        MediaSnapshot m = upload("IMG.jpg", jpeg(4000, 3000, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/IMG-scaled.jpg");
        assertThat(m.width()).hasValue(1920);
        assertThat(m.height()).hasValue(2560);
        assertThat(m.imageMeta().orElseThrow().get("orientation")).isEqualTo(IntNode.valueOf(1));
        assertThat(storage.keys()).noneMatch(key -> key.contains("rotated"));
        // the sizes are made from the upright original
        assertThat(m.sizes().get("medium").width()).isEqualTo(225);
        assertThat(m.sizes().get("medium").height()).isEqualTo(300);
        assertThat(m.sizes().get("2048x2048").file()).isEqualTo("IMG-1536x2048.jpg");
    }

    @Test
    void theThresholdTrapKeepsTheOriginalUnrotated() throws Exception {
        MediaSnapshot m = upload("x.jpg", jpeg(2561, 1000, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/x.jpg");
        assertThat(m.width()).hasValue(2561);
        assertThat(m.height()).hasValue(1000);
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.imageMeta().orElseThrow().get("orientation").textValue()).isEqualTo("6");
        assertThat(m.wordPressMetadata().has("original_image")).isFalse();
        // the sizes are still upright
        assertThat(m.sizes().get("medium").file()).isEqualTo("x-117x300.jpg");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(storage.keys()).noneMatch(key -> key.contains("scaled") || key.contains("rotated"));
    }

    @Test
    void portraitImagesShareTheirLargestFile() throws Exception {
        MediaSnapshot m = upload("x.jpg", jpeg(3000, 4000));
        assertThat(m.sizes().get("medium_large").file()).isEqualTo("x-768x1024.jpg");
        assertThat(m.sizes().get("large").file()).isEqualTo("x-768x1024.jpg");
        assertThat(m.sizes().get("medium_large").fileSize())
                .isEqualTo(m.sizes().get("large").fileSize());
        assertThat(storage.keys())
                .filteredOn(key -> key.endsWith("-768x1024.jpg"))
                .hasSize(1);
        // a shared file is counted once
        long total = 0;
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storedBytes(m)).isEqualTo(total);
        assertThat(m.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void pngsAndAnimatedGifsKeepTheirType() throws Exception {
        MediaSnapshot png = upload("x.png", MediaFixtures.png(3000, 3000));
        assertThat(png.storageKey()).isEqualTo("2026/11/x-scaled.png");
        assertThat(png.width()).hasValue(2560);
        assertThat(png.height()).hasValue(2560);
        assertThat(png.mimeType()).isEqualTo("image/png");
        assertThat(png.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/png");
        assertThat(png.sizes().get("thumbnail").file()).isEqualTo("x-150x150.png");

        MediaSnapshot gif = upload("y.gif", MediaFixtures.gif(3000, 2000, 3));
        assertThat(gif.storageKey()).isEqualTo("2026/11/y-scaled.gif");
        assertThat(gif.width()).hasValue(2560);
        assertThat(gif.height()).hasValue(1707);
        assertThat(frames("2026/11/y-scaled.gif")).isEqualTo(1);
        assertThat(frames("2026/11/y.gif")).isEqualTo(3);
        assertThat(files(gif)).allMatch(name -> name.endsWith(".gif"));
        assertThat(gif.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/gif");
        assertThat(gif.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void onlyJpegOrientationIsApplied() throws Exception {
        MediaSnapshot m = upload("x.png", MediaFixtures.pngExifOrientation(1000, 800, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/x.png");
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.sizes().get("medium").file()).isEqualTo("x-300x240.png");
    }

    @Test
    void smallImagesGetOnlyPossibleSizes() throws Exception {
        MediaSnapshot tiny = upload("tiny.jpg", jpeg(120, 90));
        assertThat(tiny.sizes()).isEmpty();
        assertThat(tiny.processing()).isEqualTo(Processing.DONE);
        assertThat(tiny.storageKey()).isEqualTo("2026/11/tiny.jpg");
        assertThat(missing(tiny.post().id())).isEmpty();
        MediaSnapshot mid = upload("mid.jpg", jpeg(1200, 800));
        assertThat(mid.sizes().keySet()).containsExactly("medium", "large", "thumbnail", "medium_large");
        assertThat(mid.originalImage()).isEmpty();
        assertThat(mid.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void withoutSubSizesTheOriginalIsKeptAsUploaded() throws Exception {
        MediaSnapshot m = upload(uploadOf("x.jpg", jpeg(4000, 3000, 6)).generateSubSizes(false));
        assertThat(m.storageKey()).isEqualTo("2026/11/x.jpg");
        assertThat(m.width()).hasValue(4000);
        assertThat(m.height()).hasValue(3000);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.imageMeta().orElseThrow().get("orientation").textValue()).isEqualTo("6");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(m.post().id())).containsExactlyElementsOf(SIX);
        assertThat(processor.decodes()).isZero();
        assertThat(storage.keys()).containsExactly("2026/11/x.jpg");
    }

    @Test
    void imagesTheProcessorCannotHandle() throws Exception {
        MediaSnapshot webp = upload("x.webp", MediaFixtures.webp());
        assertThat(webp.width()).hasValue(24);
        assertThat(webp.height()).hasValue(16);
        assertThat(webp.imageMeta()).isPresent();
        assertThat(webp.sizes()).isEmpty();
        assertThat(webp.processing()).isEqualTo(Processing.DONE);
        assertThat(fieldNames(webp.wordPressMetadata()))
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
        assertThat(missing(webp.post().id())).isEmpty();

        MediaSnapshot heic = upload("x.heic", MediaFixtures.heif("heic"));
        assertThat(fieldNames(heic.wordPressMetadata())).containsExactly("filesize");
        assertThat(heic.wordPressMetadata().get("filesize").longValue()).isEqualTo(heic.fileSize());
        assertThat(heic.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(heic.post().id())).isEmpty();

        MediaSnapshot tiff = upload("x.tif", MediaFixtures.tiff(40, 30));
        assertThat(fieldNames(tiff.wordPressMetadata())).containsExactly("filesize");
        assertThat(tiff.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(tiff.post().id())).isEmpty();

        MediaSnapshot pdf = upload("x.pdf", MediaFixtures.pdf());
        assertThat(fieldNames(pdf.wordPressMetadata())).containsExactly("filesize");
        assertThat(pdf.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(pdf.post().id())).isEmpty();

        MediaSnapshot avif = upload("x.avif", MediaFixtures.heif("avif"));
        assertThat(avif.width()).hasValue(3000);
        assertThat(avif.height()).hasValue(2000);
        assertThat(avif.imageMeta()).isPresent();
        assertThat(avif.sizes()).isEmpty();
        assertThat(avif.originalImage()).isEmpty();
        assertThat(avif.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(avif.post().id())).containsExactlyElementsOf(SIX);
        // nothing the processor cannot read is ever decoded, and post-process changes nothing
        long before = version(avif);
        assertThat(process(avif.post().id())).isEqualTo(find(avif.post().id()));
        assertThat(version(avif)).isEqualTo(before);
        assertThat(processor.decodes()).isZero();
    }

    @Test
    void audioAndVideoMetadata() throws Exception {
        MediaSnapshot mp4 = upload("clip.mp4", MediaFixtures.mp4(1280, 720, 65));
        ObjectNode video = mp4.wordPressMetadata();
        assertThat(fieldNames(video))
                .containsExactly("filesize", "mime_type", "length", "length_formatted", "width", "height");
        assertThat(video.get("filesize").longValue()).isEqualTo(mp4.fileSize());
        assertThat(video.get("mime_type").textValue()).isEqualTo("video/mp4");
        assertThat(video.get("length")).isEqualTo(IntNode.valueOf(65));
        assertThat(video.get("length_formatted").textValue()).isEqualTo("1:05");
        assertThat(video.get("width")).isEqualTo(IntNode.valueOf(1280));
        assertThat(video.get("height")).isEqualTo(IntNode.valueOf(720));
        assertThat(mp4.width()).hasValue(1280);
        assertThat(mp4.height()).hasValue(720);
        assertThat(mp4.processing()).isEqualTo(Processing.DONE);
        assertThat(missing(mp4.post().id())).isEmpty();

        MediaSnapshot wav = upload("sound.wav", MediaFixtures.wav(3));
        assertThat(fieldNames(wav.wordPressMetadata()))
                .containsExactly("filesize", "mime_type", "length", "length_formatted");
        assertThat(wav.wordPressMetadata().get("mime_type").textValue()).isEqualTo("audio/wav");
        assertThat(wav.wordPressMetadata().get("length")).isEqualTo(IntNode.valueOf(3));
        assertThat(wav.wordPressMetadata().get("length_formatted").textValue()).isEqualTo("0:03");
        assertThat(wav.width()).isEmpty();

        MediaSnapshot mp3 = upload("song.mp3", MediaFixtures.mp3(Map.of(), Optional.empty()));
        assertThat(fieldNames(mp3.wordPressMetadata())).containsExactly("filesize", "mime_type");
        assertThat(mp3.wordPressMetadata().get("mime_type").textValue()).isEqualTo("audio/mpeg");

        for (String name : List.of("clip.webm", "voice.ogg")) {
            MediaSnapshot other = upload(name, name.endsWith("webm") ? MediaFixtures.webm() : MediaFixtures.ogg());
            assertThat(fieldNames(other.wordPressMetadata())).as(name).containsExactly("filesize", "mime_type");
            assertThat(other.wordPressMetadata().get("mime_type").textValue()).isEqualTo(other.mimeType());
            assertThat(other.processing()).isEqualTo(Processing.DONE);
        }
    }

    @Test
    void aPixelBombIsStoredWithoutSizes() throws Exception {
        MediaSnapshot m = upload("bomb.png", MediaFixtures.pngHeaderOnly(40000, 40000));
        assertThat(m.width()).hasValue(40000);
        assertThat(m.height()).hasValue(40000);
        assertThat(m.imageMeta()).isPresent();
        assertThat(m.sizes()).isEmpty();
        assertThat(m.storageKey()).isEqualTo("2026/11/bomb.png");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(processor.decodes()).isZero();
        // post-process does not decode it either
        assertThat(process(m.post().id())).isEqualTo(find(m.post().id()));
        assertThat(processor.decodes()).isZero();
    }

    @Test
    void decodesNeverExceedTheConcurrencyLimit() throws Exception {
        processor.slowDecodes(300);
        List<Callable<Object>> uploads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String name = "c" + i + ".jpg";
            uploads.add(() -> upload(name, jpeg(3000, 3000)));
        }
        List<Object> results = Races.run(uploads);
        for (Object result : results) {
            assertThat(result).isInstanceOf(MediaSnapshot.class);
            MediaSnapshot m = (MediaSnapshot) result;
            assertThat(m.processing()).isEqualTo(Processing.DONE);
            assertThat(m.sizes()).hasSize(6);
        }
        assertThat(processor.decodes()).isEqualTo(3);
        assertThat(processor.maxOpenDecodes()).isEqualTo(1);
    }

    @Test
    void uploadInsideATransactionIsRefused() throws Exception {
        // the scope's own transaction
        Identities.onSite(TestData.MAIN, Identities.user(carol), () -> {
            assertThatThrownBy(
                            () -> media.upload(uploadOf("x.jpg", jpeg(64, 48)).build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(REFUSED_IN_TRANSACTION);
            QuarkusTransaction.requiringNew()
                    .run(() -> assertThatThrownBy(() ->
                                    media.upload(uploadOf("x.jpg", jpeg(64, 48)).build()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(REFUSED_IN_TRANSACTION));
            return null;
        });
        assertThat(storage.keys()).isEmpty();
        assertThat(storage.puts()).isZero();
        MediaSnapshot stored = Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> media.store(uploadOf("x.jpg", jpeg(64, 48)).build()));
        assertThat(stored.processing()).isEqualTo(Processing.PENDING);
        Identities.onSite(TestData.MAIN, Identities.user(carol), () -> {
            assertThatThrownBy(() -> media.process(stored.post().id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(REFUSED_IN_TRANSACTION);
            return null;
        });
        assertThat(find(stored.post().id()).processing()).isEqualTo(Processing.PENDING);
        // outside a transaction, a stored attachment is processed from scratch
        MediaSnapshot processed = process(stored.post().id());
        assertThat(processed.processing()).isEqualTo(Processing.DONE);
        assertThat(fieldNames(processed.wordPressMetadata()))
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
    }

    @Test
    void aFailingSizeLeavesPartialAndProcessResumes() throws Exception {
        processor.failSize(768, 576);
        MediaSnapshot m = upload("x.jpg", jpeg(4000, 3000));
        assertThat(m.sizes()).doesNotContainKey("medium_large").hasSize(5);
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(m.storageKey()).isEqualTo("2026/11/x-scaled.jpg");
        assertThat(missing(m.post().id())).containsExactly("medium_large");
        processor.reset();
        MediaSnapshot resumed = process(m.post().id());
        assertThat(resumed.sizes().keySet())
                .containsExactly("medium", "large", "thumbnail", "1536x1536", "2048x2048", "medium_large");
        assertThat(resumed.sizes().get("medium_large").file()).isEqualTo("x-768x576.jpg");
        assertThat(resumed.processing()).isEqualTo(Processing.DONE);
        assertThat(resumed.storageKey()).isEqualTo("2026/11/x-scaled.jpg");
        assertThat(storage.keys()).contains("2026/11/x-768x576.jpg");
        long total = 0;
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storedBytes(m)).isEqualTo(total);
        // nothing missing any more: unchanged, and nothing written
        long before = version(m);
        int puts = storage.puts();
        int decodes = processor.decodes();
        assertThat(process(m.post().id())).isEqualTo(resumed);
        assertThat(version(m)).isEqualTo(before);
        assertThat(storage.puts()).isEqualTo(puts);
        assertThat(processor.decodes()).isEqualTo(decodes);
    }

    @Test
    void aSizeWhoseCutThrowsIsSkipped() throws Exception {
        processor.failRegion(150, 150);
        MediaSnapshot m = upload("x.jpg", jpeg(1200, 800));
        assertThat(m.sizes().keySet()).containsExactly("medium", "large", "medium_large");
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        processor.reset();
        assertThat(process(m.post().id()).sizes().keySet())
                .containsExactly("medium", "large", "medium_large", "thumbnail");
    }

    @Test
    void aProcessorCrashNeverFailsTheUpload() throws Exception {
        processor.throwOnDecode(new IllegalStateException("the decoder crashed"));
        MediaSnapshot m = upload("x.jpg", jpeg(4000, 3000));
        assertThat(m.storageKey()).isEqualTo("2026/11/x.jpg");
        assertThat(m.width()).hasValue(4000);
        assertThat(m.height()).hasValue(3000);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.imageMeta()).isPresent();
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(fieldNames(m.wordPressMetadata()))
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
        assertThat(storage.keys()).containsExactly("2026/11/x.jpg");
    }

    @Test
    void twoProcessRunsAtOnceAgree() throws Exception {
        processor.throwOnDecode(new IllegalStateException("not now"));
        MediaSnapshot base = upload("x.jpg", jpeg(4000, 3000));
        assertThat(base.sizes()).isEmpty();
        processor.reset();
        int puts = storage.puts();
        PostId id = base.post().id();
        List<Object> results = Races.run(List.of(() -> process(id), () -> process(id)));
        for (Object result : results) {
            assertThat(result).isInstanceOf(MediaSnapshot.class);
        }
        MediaSnapshot after = find(id);
        assertThat(after.sizes().keySet())
                .containsExactly("medium", "large", "thumbnail", "medium_large", "1536x1536", "2048x2048");
        assertThat(after.processing()).isEqualTo(Processing.DONE);
        // the main-file copy the crashed upload could not make is made once too
        assertThat(after.storageKey()).isEqualTo("2026/11/x-scaled.jpg");
        assertThat(storage.puts() - puts).as("files stored").isEqualTo(7);
        long total = 0;
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storedBytes(after)).isEqualTo(total);
    }

    @Test
    void processNeedsEditPost() throws Exception {
        MediaSnapshot bobs =
                as(bob, () -> media.upload(uploadOf("b.jpg", jpeg(64, 48)).build()));
        assertThatThrownBy(() -> as(erin, () -> media.process(bobs.post().id())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> as(carol, () -> media.process(bobs.post().id())))
                .isInstanceOf(ForbiddenException.class);
        assertThat(as(bob, () -> media.process(bobs.post().id()))).isEqualTo(bobs);
    }

    @Test
    void theRunIsAnnounced() throws Exception {
        MediaSnapshot m = upload("x.jpg", jpeg(1200, 800));
        assertThat(recorder.processed).hasSize(1);
        MediaProcessed event = recorder.processed.get(0);
        assertThat(event.site()).isEqualTo(TestData.MAIN);
        assertThat(event.at()).isEqualTo(NOW);
        assertThat(event.media()).isEqualTo(m);
        assertThat(event.media().processing()).isEqualTo(Processing.DONE);
        assertThat(event.media().sizes()).hasSize(4);
        // a post-process with nothing to do announces nothing
        process(m.post().id());
        assertThat(recorder.processed).hasSize(1);
        // one that makes a size announces once more, with its final state
        processor.failSize(150, 150);
        MediaSnapshot partial = upload("y.jpg", jpeg(1200, 800));
        assertThat(recorder.processed).hasSize(2);
        assertThat(recorder.processed.get(1).media()).isEqualTo(partial);
        processor.reset();
        MediaSnapshot done = process(partial.post().id());
        assertThat(recorder.processed).hasSize(3);
        assertThat(recorder.processed.get(2).media()).isEqualTo(done);
        assertThat(done.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void missingSizesAndProcessNeedAnAttachment() throws Exception {
        PostId page = as(
                        bob,
                        () -> posts.create(PostChanges.builder()
                                .type("page")
                                .title("Not media")
                                .build()))
                .id();
        assertThatThrownBy(() -> missing(page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(bob, () -> media.process(page))).isInstanceOf(IllegalArgumentException.class);
        PostId missingPost = PostId.of(Long.MAX_VALUE - 1);
        assertThatThrownBy(() -> missing(missingPost)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> process(missingPost)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fix round: rotation in post-process, the threshold edge, the main-file retry, stored sizes, errors ---------

    @Test
    void aPostProcessOfARotatedAttachmentCutsTheUprightOriginal() throws Exception {
        processor.failSize(240, 300);
        MediaSnapshot m = upload("photo.jpg", jpeg(1000, 800, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/photo-rotated.jpg");
        assertThat(m.imageMeta().orElseThrow().get("orientation")).isEqualTo(IntNode.valueOf(1));
        assertThat(m.sizes()).doesNotContainKey("medium");
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        processor.reset();
        // the stored metadata says 1 now: the orientation comes from the original file
        MediaSnapshot resumed = process(m.post().id());
        assertThat(resumed.sizes().get("medium").file()).isEqualTo("photo-240x300.jpg");
        assertThat(resumed.sizes().get("medium").width()).isEqualTo(240);
        assertThat(resumed.sizes().get("medium").height()).isEqualTo(300);
        assertThat(resumed.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void anImageAtTheThresholdIsRotatedNotScaled() throws Exception {
        MediaSnapshot m = upload("edge.jpg", jpeg(2560, 1000, 6));
        assertThat(m.storageKey()).isEqualTo("2026/11/edge-rotated.jpg");
        assertThat(m.width()).hasValue(1000);
        assertThat(m.height()).hasValue(2560);
        assertThat(m.originalImage()).contains("edge.jpg");
        assertThat(storage.keys()).noneMatch(key -> key.contains("scaled"));
    }

    @Test
    void aPostProcessRetriesAFailedMainFileCopy() throws Exception {
        processor.failSize(2560, 1920);
        MediaSnapshot m = upload("x.jpg", jpeg(4000, 3000));
        assertThat(m.storageKey()).isEqualTo("2026/11/x.jpg");
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.sizes()).hasSize(6);
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(missing(m.post().id())).isEmpty();
        processor.reset();
        MediaSnapshot retried = process(m.post().id());
        assertThat(retried.storageKey()).isEqualTo("2026/11/x-scaled.jpg");
        assertThat(retried.originalImage()).contains("x.jpg");
        assertThat(retried.width()).hasValue(2560);
        assertThat(retried.height()).hasValue(1920);
        assertThat(retried.sizes()).isEqualTo(m.sizes());
        assertThat(retried.processing()).isEqualTo(Processing.DONE);
        long total = 0;
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storedBytes(retried)).isEqualTo(total);
        // complete now: nothing more to do
        long before = version(retried);
        assertThat(process(m.post().id())).isEqualTo(retried);
        assertThat(version(retried)).isEqualTo(before);
        // an upload without sizes is done, never given a main-file copy by a post-process
        MediaSnapshot kept = upload(uploadOf("k.jpg", jpeg(4000, 3000)).generateSubSizes(false));
        assertThat(process(kept.post().id()).storageKey()).isEqualTo("2026/11/k.jpg");
    }

    @Test
    void storedSizesAreTheStoragesOwn() throws Exception {
        // the original is written by the store; every later write gains seven bytes in the storage
        storage.padWritesAfter(1, 7);
        MediaSnapshot m = upload("x.jpg", jpeg(4000, 3000));
        assertThat(m.fileSize()).isEqualTo(storage.size(m.storageKey()).orElseThrow());
        for (MediaSize size : m.sizes().values()) {
            assertThat(size.fileSize())
                    .isEqualTo(storage.size(folderKey(size.file())).orElseThrow());
        }
        long total = 0;
        for (String key : storage.keys()) {
            total += storage.size(key).orElseThrow();
        }
        assertThat(storedBytes(m)).isEqualTo(total);
    }

    @Test
    void runningOutOfMemoryWhileDecodingCountsAsADecodeFailure() throws Exception {
        processor.errorOnDecode(new OutOfMemoryError("the test heap is full"));
        MediaSnapshot m = upload("x.jpg", jpeg(1200, 800));
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.width()).hasValue(1200);
    }

    // ---- fix round T14d: failures that must not leave the row pending, failed turns ------------------------------

    @Test
    void aStorageThatCannotBeReadEndsFailed() throws Exception {
        storage.openFails(new IllegalStateException("the bucket is gone"));
        MediaSnapshot m = upload("x.jpg", jpeg(1200, 800));
        assertThat(m.processing()).isEqualTo(Processing.FAILED);
        assertThat(m.sizes()).isEmpty();
        storage.openFails(null);
        assertThat(find(m.post().id()).processing()).isEqualTo(Processing.FAILED);
    }

    @Test
    void anUnexpectedFailureInARunEndsPartial() throws Exception {
        MediaSnapshot stored = Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> media.store(uploadOf("x.jpg", jpeg(1200, 800)).build()));
        processor.throwOnSupports(new IllegalStateException("a bug in the processor"));
        MediaSnapshot m = process(stored.post().id());
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(find(stored.post().id()).processing()).isEqualTo(Processing.PARTIAL);
    }

    @Test
    void aFailedTurnCountsAsADecodeFailure() throws Exception {
        processor.failTurns(new OutOfMemoryError("the test heap is full"));
        MediaSnapshot m = upload("photo.jpg", jpeg(1000, 800, 6));
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.storageKey()).isEqualTo("2026/11/photo.jpg");
        assertThat(m.width()).hasValue(1000);
        processor.failTurns(new IllegalStateException("the turn failed"));
        MediaSnapshot n = upload("other.jpg", jpeg(1000, 800, 6));
        assertThat(n.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(n.sizes()).isEmpty();
        // a post-process turns it again; failing again, it changes nothing
        assertThat(process(n.post().id()).sizes()).isEmpty();
        processor.reset();
        MediaSnapshot resumed = process(n.post().id());
        assertThat(resumed.sizes().get("medium").file()).isEqualTo("other-240x300.jpg");
    }
}
