package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.UploadCheck;
import org.quarkpress.media.UploadFilter;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Uploads through {@code POST /wp/v2/media}, as WordPress stores them: the 201 with the attachment in the edit
 * context, its {@code Location} and {@code X-WP-Upload-Attachment-ID}; the fields of a multipart or a raw upload; the
 * statuses an attachment takes; the post fields a failed preparation drops (the caption kept); the featured image and
 * the meta written after the row, whose refusals answer without the header and leave the attachment unprocessed; a
 * crash after the row, answered 500 with the header; and no temporary file left behind. Members of the main site: the
 * editor bob, the author carol, a contributor who may upload (the uploader), a subscriber who may upload (the
 * librarian); alice is a super admin.
 */
class MediaCreateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaCreateDbTest.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class,
                    CrashingProcessor.class,
                    CrashingProcessor.Crashing.class,
                    AttachmentMeta.class,
                    StatusFilter.class)
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-create");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final List<String> ALL_SIZES =
            List.of("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");

    static AgroalDataSource data;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId uploader;
    static UserId librarian;

    @Inject
    CrashingProcessor processor;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        alice = TestData.profile(data, "administrator");
        TestData.superAdmin(data, alice);
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
        // a role with upload_files but not publish_posts: a contributor who may upload
        uploader = TestData.profile(data, "uploader");
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (1, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                uploader.value(),
                "[\"contributor\"]",
                "{\"upload_files\": true}");
        // a role with upload_files but not edit_posts: a subscriber who may upload, and edit nothing
        librarian = TestData.profile(data, "librarian");
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (1, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                librarian.value(),
                "[\"subscriber\"]",
                "{\"upload_files\": true}");
    }

    // ---- the test application's beans

    /**
     * ImageIO's processor, which, while armed, throws {@link InternalError} when the second image of a run is encoded
     * (the second size): an error the media module does not turn into a partial attachment.
     */
    @Alternative
    @Priority(10)
    @ApplicationScoped
    public static class CrashingProcessor implements ImageProcessor {

        private final ImageProcessor delegate = new ImageIoProcessor(100_000_000L);
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger encodes = new AtomicInteger();

        void arm() {
            encodes.set(0);
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        @Override
        public boolean supports(String mimeType) {
            return delegate.supports(mimeType);
        }

        @Override
        public Optional<ImageInfo> read(Path file) {
            return delegate.read(file);
        }

        @Override
        public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
            return new Crashing(delegate.decode(file, mimeType), this);
        }

        /** A decoded image whose encodes count towards the crash. */
        public static final class Crashing implements DecodedImage {

            private final DecodedImage image;
            private final CrashingProcessor owner;

            Crashing(DecodedImage image, CrashingProcessor owner) {
                this.image = image;
                this.owner = owner;
            }

            @Override
            public int width() {
                return image.width();
            }

            @Override
            public int height() {
                return image.height();
            }

            @Override
            public DecodedImage oriented(int exifOrientation) {
                return new Crashing(image.oriented(exifOrientation), owner);
            }

            @Override
            public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
                return new Crashing(image.region(x, y, w, h, targetWidth, targetHeight), owner);
            }

            @Override
            public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
                if (owner.armed.get() && owner.encodes.incrementAndGet() == 2) {
                    throw new InternalError("x");
                }
                return image.encode(target, mimeType, quality);
            }

            @Override
            public void close() {
                image.close();
            }
        }
    }

    /**
     * Two attachment meta keys: {@code qp_locked}, which no one may write, and {@code qp_boom}, whose permission check
     * fails unexpectedly.
     */
    @ApplicationScoped
    public static class AttachmentMeta implements MetaKeyProvider {

        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(
                    MetaKeyDefinition.builder("qp_locked")
                            .subtype("attachment")
                            .type("string")
                            .single()
                            .showInRest()
                            .auth((allowed, key, post, user, capability) -> false)
                            .build(),
                    MetaKeyDefinition.builder("qp_boom")
                            .subtype("attachment")
                            .type("string")
                            .single()
                            .showInRest()
                            .auth((allowed, key, post, user, capability) -> {
                                throw new IllegalStateException("the meta store is gone");
                            })
                            .build());
        }
    }

    /** Records the transaction status each upload's checks run in. */
    @ApplicationScoped
    public static class StatusFilter implements UploadFilter {

        static final List<Integer> statuses = new CopyOnWriteArrayList<>();

        @Inject
        TransactionManager transactions;

        @Override
        public Optional<String> refuse(UploadCheck upload) {
            try {
                statuses.add(transactions.getStatus());
            } catch (SystemException failure) {
                throw new IllegalStateException(failure);
            }
            return Optional.empty();
        }
    }

    // ---- tests

    @Test
    void aMultipartUploadAnswers201WithTheHeaderAndLocation() {
        PostId draft = draft(carol);
        Response answer = multipart(carol, "IMG_1234.jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800))
                .multiPart("post", Long.toString(draft.value()))
                .multiPart("alt_text", "A")
                .multiPart("caption", "C")
                .post(MEDIA);
        JsonNode body = created(answer);
        long id = body.get("id").longValue();
        assertThat(answer.header("X-WP-Upload-Attachment-ID")).isEqualTo(Long.toString(id));
        assertThat(answer.header("Location")).isEqualTo("http://localhost/wp-json/wp/v2/media/" + id);
        assertThat(answer.header("Allow")).isEqualTo("GET, POST");
        assertThat(answer.headers().getValues("Link"))
                .containsExactly("<http://localhost/wp-json/>; rel=\"https://api.w.org/\"");
        // the edit context, whatever was asked
        assertThat(body.at("/title/raw").asText()).isEqualTo("IMG_1234");
        assertThat(body.get("alt_text").asText()).isEqualTo("A");
        assertThat(body.at("/caption/raw").asText()).isEqualTo("C");
        assertThat(body.get("post").longValue()).isEqualTo(draft.value());
        assertThat(body.get("status").asText()).isEqualTo("inherit");
        assertThat(body.get("author").longValue()).isEqualTo(carol.value());
        List<String> sizes = new ArrayList<>();
        body.at("/media_details/sizes").fieldNames().forEachRemaining(sizes::add);
        // in WordPress's order of making them, the full size last
        assertThat(sizes).containsExactly("medium", "large", "thumbnail", "medium_large", "full");
        assertThat(body.get("missing_image_sizes")).isEmpty();
        assertThat(body.at("/media_details/width").intValue()).isEqualTo(1200);
    }

    @Test
    void aRawUploadTakesItsFieldsFromTheQuery() {
        Response answer = Http.as(bearer(carol))
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=\"logo.png\"")
                .body(MediaFixtures.png(16, 16))
                .post(MEDIA + "?title=Logo");
        JsonNode body = created(answer);
        assertThat(body.at("/title/raw").asText()).isEqualTo("Logo");
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(body.at("/media_details/file").asText())
                .isIn(
                        String.format("%04d/%02d/logo.png", today.getYear(), today.getMonthValue()),
                        String.format(
                                "%04d/%02d/logo.png",
                                today.minusDays(1).getYear(), today.minusDays(1).getMonthValue()));
        assertThat(answer.header("X-WP-Upload-Attachment-ID"))
                .isEqualTo(Long.toString(body.get("id").longValue()));
        // without a title, a raw upload's comes from the stored name: unique, and without the header's path
        String name = "raw" + suffix();
        JsonNode first = created(raw(carol, "attachment; filename=\"../" + name + ".png\""));
        assertThat(first.at("/title/raw").asText()).isEqualTo(name);
        JsonNode second = created(raw(carol, "attachment; filename=" + name + ".png"));
        assertThat(second.at("/title/raw").asText()).isEqualTo(name + "-1");
        assertThat(second.get("slug").asText()).isEqualTo(name + "-1");
    }

    private static Response raw(UserId user, String disposition) {
        return Http.as(bearer(user))
                .contentType("image/png")
                .header("Content-Disposition", disposition)
                .body(MediaFixtures.png(8, 8))
                .post(MEDIA);
    }

    @Test
    void statusesOnCreate() {
        assertThat(created(jpeg(carol, "status", "draft")).get("status").asText())
                .isEqualTo("inherit");
        assertThat(created(jpeg(carol, "status", "pending")).get("status").asText())
                .isEqualTo("inherit");
        assertThat(created(jpeg(carol, "status", "publish")).get("status").asText())
                .isEqualTo("inherit");
        assertThat(created(jpeg(carol, "status", "private")).get("status").asText())
                .isEqualTo("private");
        for (String internal : List.of("inherit", "trash")) {
            Response refused = jpeg(carol, "status", internal);
            JsonNode body = error(refused, 400, "rest_invalid_param", "Invalid parameter(s): status");
            assertThat(body.at("/data/params/status").asText())
                    .isEqualTo("status is not one of publish, future, draft, pending, and private.");
            assertThat(body.at("/data/details/status/code").asText()).isEqualTo("rest_not_in_enum");
            assertThat(refused.header("X-WP-Upload-Attachment-ID")).isNull();
        }
        // a future date never schedules an attachment
        JsonNode future = created(jpeg(carol, "date", "2099-01-01T10:00:00"));
        assertThat(future.get("status").asText()).isEqualTo("inherit");
        assertThat(future.get("date").asText()).isEqualTo("2099-01-01T10:00:00");
    }

    @Test
    void fieldErrorsBeforeTheInsertAreIgnored() {
        String name = "f" + suffix();
        PostId draft = draft(bob);
        // an author that is no user, sent by someone who may name another author
        JsonNode body = created(multipart(alice, name + ".jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .multiPart("generate_sub_sizes", "false")
                .multiPart("author", "999999")
                .multiPart("title", "Kept?")
                .multiPart("caption", "c")
                .multiPart("description", "d")
                .multiPart("post", Long.toString(draft.value()))
                .post(MEDIA));
        // the parent is kept, as WordPress keeps it whatever happens to the post fields
        assertThat(body.get("post").longValue()).isEqualTo(draft.value());
        assertThat(body.get("author").longValue()).isEqualTo(alice.value());
        assertThat(body.at("/title/raw").asText()).isEqualTo(name);
        assertThat(body.at("/caption/raw").asText()).isEqualTo("c");
        // the stored caption runs the registered filters alone, where WordPress wraps it in a paragraph
        assertThat(body.at("/caption/rendered").asText()).isEqualTo("c");
        assertThat(body.at("/description/raw").asText()).isEmpty();
        assertThat(body.get("status").asText()).isEqualTo("inherit");
        // an id the create check lets through (0) names no post: the same shape
        JsonNode zero = created(multipart(alice, name + "-id.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .multiPart("generate_sub_sizes", "false")
                .multiPart("id", "0")
                .multiPart("title", "Kept?")
                .multiPart("caption", "c")
                .post(MEDIA));
        assertThat(zero.at("/title/raw").asText()).isEqualTo(name + "-id");
        assertThat(zero.at("/caption/raw").asText()).isEqualTo("c");
        // a status the caller may not set drops the title with it
        JsonNode privateAsked = created(multipart(uploader, name + "-p.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .multiPart("generate_sub_sizes", "false")
                .multiPart("status", "private")
                .multiPart("title", "Requested")
                .post(MEDIA));
        assertThat(privateAsked.get("status").asText()).isEqualTo("inherit");
        assertThat(privateAsked.at("/title/raw").asText()).isEqualTo(name + "-p");
        // carol may publish: her private upload keeps its fields
        JsonNode carols = created(jpeg(carol, "status", "private", "title", "Requested"));
        assertThat(carols.get("status").asText()).isEqualTo("private");
        assertThat(carols.at("/title/raw").asText()).isEqualTo("Requested");
        // the fields after the row still apply: the alternative text
        JsonNode alt = created(multipart(alice, name + "-alt.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .multiPart("generate_sub_sizes", "false")
                .multiPart("author", "999999")
                .multiPart("alt_text", "")
                .post(MEDIA));
        assertThat(alt.get("alt_text").asText()).isEmpty();
    }

    @Test
    void featuredMediaOnAnImageIs400AfterTheRowExists() {
        String name = "fm" + suffix();
        Response refused = multipart(carol, name + ".jpg", "image/jpeg", MediaFixtures.jpeg(64, 48))
                .multiPart("featured_media", "0")
                .post(MEDIA);
        error(
                refused,
                400,
                "rest_no_featured_media",
                "This site does not support post thumbnails on attachments with MIME type image/jpeg.");
        assertThat(refused.header("X-WP-Upload-Attachment-ID")).isNull();
        long id = attachmentTitled(name);
        JsonNode listed = json(Http.as(bearer(carol))
                .get(MEDIA + "?context=edit&_fields=id,media_details,missing_image_sizes&include=" + id)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("media_details").toString()).isEqualTo("{}");
        assertThat(texts(listed.get(0).get("missing_image_sizes"))).isEqualTo(ALL_SIZES);
        assertThat(processing(id)).isEqualTo("pending");
    }

    @Test
    void featuredMediaOnAudio() {
        MediaSnapshot image = MediaFixturesForRest.upload(TestData.MAIN, carol, "cover" + suffix() + ".jpg", false);
        long imageId = image.post().id().value();
        JsonNode audio = created(multipart(carol, "song" + suffix() + ".mp3", "audio/mpeg", mp3())
                .multiPart("featured_media", Long.toString(imageId))
                .post(MEDIA));
        assertThat(audio.get("featured_media").longValue()).isEqualTo(imageId);
        // 0 removes, which a new attachment has nothing to remove
        assertThat(created(multipart(carol, "zero" + suffix() + ".mp3", "audio/mpeg", mp3())
                                .multiPart("featured_media", "0")
                                .post(MEDIA))
                        .get("featured_media")
                        .longValue())
                .isZero();
        String name = "bad" + suffix();
        Response refused = multipart(carol, name + ".mp3", "audio/mpeg", mp3())
                .multiPart("featured_media", "999999")
                .post(MEDIA);
        error(refused, 400, "rest_invalid_featured_media", "Invalid featured media ID.");
        assertThat(refused.header("X-WP-Upload-Attachment-ID")).isNull();
        assertThat(processing(attachmentTitled(name))).isEqualTo("pending");
    }

    @Test
    void theFeaturedImageOfAnUploadIsSetWhateverTheCallerMayEdit() {
        // the uploader may not edit the new attachment, yet its cover is set, as an update sets it
        MediaSnapshot image = MediaFixturesForRest.upload(TestData.MAIN, carol, "cover" + suffix() + ".jpg", false);
        long imageId = image.post().id().value();
        JsonNode audio = created(multipart(librarian, "song" + suffix() + ".mp3", "audio/mpeg", mp3())
                .multiPart("featured_media", Long.toString(imageId))
                .post(MEDIA));
        assertThat(audio.get("featured_media").longValue()).isEqualTo(imageId);
        assertThat(audio.get("author").longValue()).isEqualTo(librarian.value());
    }

    @Test
    void aMetaRefusalAfterTheRowLeavesItUnprocessed() {
        String name = "meta" + suffix();
        Response refused = multipart(carol, name + ".jpg", "image/jpeg", MediaFixtures.jpeg(64, 48))
                .multiPart("meta[qp_locked]", "x")
                .post(MEDIA);
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(403);
        assertThat(json(refused.asString()).get("code").asText()).isEqualTo("rest_cannot_update");
        assertThat(refused.header("X-WP-Upload-Attachment-ID")).isNull();
        assertThat(processing(attachmentTitled(name))).isEqualTo("pending");
    }

    @Test
    void generateSubSizesFalseMakesNoSizes() {
        JsonNode body = created(multipart(carol, "ns" + suffix() + ".jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800))
                .multiPart("generate_sub_sizes", "false")
                .post(MEDIA));
        assertThat(body.at("/media_details/sizes").toString()).isEqualTo("{}");
        assertThat(texts(body.get("missing_image_sizes")))
                .containsExactly("thumbnail", "medium", "medium_large", "large");
    }

    @Test
    void templateIsNeverStoredOnCreate() {
        JsonNode body = created(jpeg(carol, "template", ""));
        long id = body.get("id").longValue();
        assertThat(body.get("template").asText()).isEmpty();
        assertThat(Jdbc.strings(data, "select meta from qp_post where id = ?", id)
                        .get(0))
                .doesNotContain("_wp_page_template");
    }

    @Test
    void aProcessingCrashAnswers500WithTheHeader() {
        String name = "crash" + suffix();
        processor.arm();
        Response crashed;
        try {
            crashed = multipart(carol, name + ".jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800))
                    .post(MEDIA);
        } finally {
            processor.disarm();
        }
        error(crashed, 500, "internal_server_error", "There has been a critical error on this website.");
        assertThat(crashed.contentType()).startsWith("application/json");
        long id = attachmentTitled(name);
        assertThat(crashed.header("X-WP-Upload-Attachment-ID")).isEqualTo(Long.toString(id));
        // the row stays with what was saved before the crash (the first size), for the client's post-process to finish
        assertThat(processing(id)).isEqualTo("partial");
        Http.as(bearer(carol)).get(MEDIA + "/" + id).then().statusCode(200);
    }

    @Test
    void aFailureInTheAfterStoreStepAnswers500WithTheHeader() {
        String name = "boom" + suffix();
        Response failed = multipart(carol, name + ".jpg", "image/jpeg", MediaFixtures.jpeg(64, 48))
                .multiPart("meta[qp_boom]", "x")
                .post(MEDIA);
        error(failed, 500, "internal_server_error", "There has been a critical error on this website.");
        assertThat(failed.contentType()).startsWith("application/json");
        long id = attachmentTitled(name);
        assertThat(failed.header("X-WP-Upload-Attachment-ID")).isEqualTo(Long.toString(id));
        assertThat(processing(id)).isEqualTo("pending");
    }

    @Test
    void rawUnsupportedImageTypesAreStored() {
        // ImageIO writes no WebP: a raw body is stored, as the multipart refusal applies to the file part alone
        JsonNode body = created(Http.as(bearer(carol))
                .contentType("image/webp")
                .header("Content-Disposition", "attachment; filename=\"w" + suffix() + ".webp\"")
                .body(MediaFixtures.webp())
                .post(MEDIA));
        assertThat(body.get("mime_type").asText()).isEqualTo("image/webp");
        assertThat(body.at("/media_details/sizes").toString()).isEqualTo("{}");
    }

    @Test
    void uploadsRunOutsideAnyTransaction() {
        StatusFilter.statuses.clear();
        created(jpeg(carol));
        created(Http.as(bearer(carol))
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=\"t.png\"")
                .body(MediaFixtures.png(8, 8))
                .post(MEDIA));
        assertThat(StatusFilter.statuses).containsExactly(Status.STATUS_NO_TRANSACTION, Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void noTemporaryFileOutlivesAnUpload() throws Exception {
        Path spool = Path.of(fsDirectory(), ".tmp");
        Path uploads =
                Path.of(ConfigProvider.getConfig().getValue("quarkus.http.body.uploads-directory", String.class));
        Set<Path> spoolBefore = files(spool);
        Set<Path> uploadsBefore = files(uploads);
        created(jpeg(carol));
        created(Http.as(bearer(carol))
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=\"tmp.png\"")
                .body(MediaFixtures.png(8, 8))
                .post(MEDIA));
        // refusals after the row, multipart and raw
        multipart(carol, "t" + suffix() + ".jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .multiPart("featured_media", "0")
                .post(MEDIA)
                .then()
                .statusCode(400);
        Http.as(bearer(carol))
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=\"tmp.png\"")
                .queryParam("featured_media", "0")
                .body(MediaFixtures.png(8, 8))
                .post(MEDIA)
                .then()
                .statusCode(400);
        // and a raw body whose processing crashes after the row
        processor.arm();
        try {
            Http.as(bearer(carol))
                    .contentType("image/png")
                    .header("Content-Disposition", "attachment; filename=\"crash.png\"")
                    .body(MediaFixtures.png(800, 600))
                    .post(MEDIA)
                    .then()
                    .statusCode(500);
        } finally {
            processor.disarm();
        }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline
                && !(added(spool, spoolBefore).isEmpty()
                        && added(uploads, uploadsBefore).isEmpty())) {
            Thread.sleep(50);
        }
        assertThat(added(spool, spoolBefore)).isEmpty();
        assertThat(added(uploads, uploadsBefore)).isEmpty();
    }

    // ---- helpers

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static RequestSpecification multipart(UserId user, String name, String type, byte[] bytes) {
        return Http.as(bearer(user)).multiPart("file", name, bytes, type);
    }

    /** An 8x8 JPEG uploaded by {@code user} with the given text fields. */
    private static Response jpeg(UserId user, String... fields) {
        RequestSpecification request = multipart(user, "j" + suffix() + ".jpg", "image/jpeg", MediaFixtures.jpeg(8, 8));
        for (int i = 0; i < fields.length; i += 2) {
            request.multiPart(fields[i], fields[i + 1]);
        }
        return request.post(MEDIA);
    }

    private static JsonNode created(Response answer) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(201);
        JsonNode body = json(answer.asString());
        assertThat(answer.header("X-WP-Upload-Attachment-ID"))
                .isEqualTo(Long.toString(body.get("id").longValue()));
        return body;
    }

    private static JsonNode error(Response answer, int status, String code, String message) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(status);
        JsonNode body = json(answer.asString());
        assertThat(body.get("code").asText()).as(answer.asString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.at("/data/status").intValue()).isEqualTo(status);
        return body;
    }

    private static long attachmentTitled(String title) {
        List<String> ids =
                Jdbc.strings(data, "select id from qp_post where type = 'attachment' and title = ? order by id", title);
        assertThat(ids).as("attachments titled " + title).hasSize(1);
        return Long.parseLong(ids.get(0));
    }

    private static String processing(long id) {
        return Jdbc.strings(data, "select processing from qp_media where post_id = ?", id)
                .get(0);
    }

    private static PostId draft(UserId author) {
        return PostFixtures.builder(TestData.MAIN)
                .type("post")
                .status("draft")
                .author(author.value())
                .title("draft " + suffix())
                .modifiedAt("2026-01-01T00:00:00Z")
                .insert(data);
    }

    private static byte[] mp3() {
        return MediaFixtures.mp3(Map.of(), Optional.empty());
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String fsDirectory() {
        return ConfigProvider.getConfig().getValue("quarkpress.media.fs.directory", String.class);
    }

    private static Set<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return new HashSet<>();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return new HashSet<>(walk.filter(Files::isRegularFile).toList());
        }
    }

    private static Set<Path> added(Path root, Set<Path> before) {
        try {
            Set<Path> now = files(root);
            now.removeAll(before);
            return now;
        } catch (IOException | UncheckedIOException vanishedWhileListing) {
            return Set.of(Path.of("listing failed"));
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
