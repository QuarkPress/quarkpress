package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * {@code POST /wp/v2/media/<id>/post-process}, the editor's recovery of an upload whose sizes were not all made: its
 * checks in WordPress's order (the arguments, then the update permission); then the attachment's missing sizes made
 * and the attachment answered 200 in the edit context, whatever state its metadata is in (none yet, a failed run, a
 * crashed upload, a file that has no sizes); a crash while making them answered 200 all the same; and five calls at once
 * agreeing on one set of sizes. Members of the main site: the editor bob, the author carol.
 */
class MediaPostProcessDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaPostProcessDbTest.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class,
                    CrashingProcessor.class,
                    CrashingProcessor.Crashing.class)
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-post-process");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final String ACTION = "{\"action\":\"create-image-subsizes\"}";

    /** The sizes WordPress's default sizes give a 1200x800 image, in generation order. */
    private static final List<String> SIZES = List.of("medium", "large", "thumbnail", "medium_large");

    static AgroalDataSource data;
    static UserId bob;
    static UserId carol;

    @Inject
    CrashingProcessor processor;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
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
        private final AtomicReference<Runnable> beforeDecode = new AtomicReference<>();

        void arm() {
            encodes.set(0);
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        /** Runs {@code work} once, when the next image is decoded, before decoding it. */
        void duringNextDecode(Runnable work) {
            beforeDecode.set(work);
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
            Runnable work = beforeDecode.getAndSet(null);
            if (work != null) {
                work.run();
            }
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

    // ---- tests

    @Test
    void theChecksComeInWordPresssOrder() {
        long bobs = sizeless(bob);
        // the arguments come before the permission: anonymous without an action
        Response missing = postProcess(null, bobs, "{}");
        JsonNode body = error(missing, 400, "rest_missing_callback_param", "Missing parameter(s): action");
        assertThat(body.at("/data/params").toString()).isEqualTo("[\"action\"]");
        // an action outside the one-value enum
        Response foo = postProcess(null, bobs, "{\"action\":\"foo\"}");
        body = error(foo, 400, "rest_invalid_param", "Invalid parameter(s): action");
        assertThat(body.at("/data/params/action").asText()).isEqualTo("action is not create-image-subsizes.");
        assertThat(body.at("/data/details/action/code").asText()).isEqualTo("rest_not_in_enum");
        assertThat(body.at("/data/details/action/message").asText()).isEqualTo("action is not create-image-subsizes.");
        // a post that is no attachment, an id the site does not have
        PostId post = TestData.post(data, TestData.MAIN, carol, "post", "draft");
        error(postProcess(carol, post.value(), ACTION), 404, "rest_post_invalid_id", "Invalid post ID.");
        error(postProcess(carol, 999_999_999L, ACTION), 404, "rest_post_invalid_id", "Invalid post ID.");
        // the update permission: another author's attachment, an anonymous caller
        Response others = postProcess(carol, bobs, ACTION);
        error(others, 403, "rest_cannot_edit", "Sorry, you are not allowed to edit this post.");
        error(
                postProcess(null, bobs, ACTION),
                401,
                "rest_cannot_edit",
                "Sorry, you are not allowed to edit this post.");
        assertThat(sizes(bobs)).isEqualTo("{}");
        // the route has no GET
        error(
                Http.as(bearer(bob)).get(MEDIA + "/" + bobs + "/post-process"),
                404,
                "rest_no_route",
                "No route was found matching the URL and request method.");
    }

    @Test
    void anotherSitesAttachmentIs404AndUntouched() {
        SiteId other = TestData.site(data);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, other, editor, "[\"editor\"]");
        TestData.member(data, TestData.MAIN, editor, "[\"editor\"]");
        long theirs = MediaFixturesForRest.upload(other, editor, "o" + suffix() + ".jpg", false)
                .post()
                .id()
                .value();
        String version = versionOf(theirs);
        // asked on the main site, the id names no attachment of it
        error(postProcess(editor, theirs, ACTION), 404, "rest_post_invalid_id", "Invalid post ID.");
        assertThat(versionOf(theirs)).isEqualTo(version);
    }

    @Test
    void anAttachmentDeletedWhileItIsProcessedIs404() {
        long id = sizeless(carol);
        // the attachment is deleted after the permission passed, while its image is decoded
        processor.duringNextDecode(() -> Jdbc.update(data, "delete from qp_post where id = ?", id));
        Response answer = postProcess(carol, id, ACTION);
        error(answer, 404, "rest_post_invalid_id", "Invalid post ID.");
    }

    @Test
    void everyStateAnswers200() {
        // sizes not asked for at upload: post-process makes the fitting ones
        long sizeless = sizeless(carol);
        Response made = postProcess(carol, sizeless, ACTION);
        JsonNode answer = ok(made);
        assertThat(answer.get("id").longValue()).isEqualTo(sizeless);
        assertThat(fieldNames(answer.at("/media_details/sizes"))).containsExactlyElementsOf(withFull(SIZES));
        assertThat(answer.get("missing_image_sizes").size()).isZero();
        // the edit context, whatever was asked for, with the item's links; neither address nor upload header
        assertThat(answer.has("permalink_template")).isTrue();
        assertThat(answer.has("_links")).isTrue();
        assertThat(made.header("Location")).isNull();
        assertThat(made.header("X-WP-Upload-Attachment-ID")).isNull();
        assertThat(made.header("Allow")).isEqualTo("POST");
        assertThat(processing(sizeless)).isEqualTo("done");

        // a failed attachment is processed from scratch
        long failed = sizeless(carol);
        Jdbc.update(data, "update qp_media set processing = 'failed' where post_id = ?", failed);
        JsonNode redone = ok(postProcess(carol, failed, ACTION));
        assertThat(fieldNames(redone.at("/media_details/sizes"))).containsExactlyElementsOf(withFull(SIZES));
        assertThat(processing(failed)).isEqualTo("done");

        // a PDF and a HEIC without readable dimensions have nothing to make: answered as they are, nothing written
        for (String name : List.of("doc" + suffix() + ".pdf", "img" + suffix() + ".heic")) {
            long id = name.endsWith(".pdf")
                    ? MediaFixturesForRest.upload(TestData.MAIN, carol, name, true)
                            .post()
                            .id()
                            .value()
                    : uploadedRaw(carol, name, "image/heic", MediaFixtures.heif("heic"));
            String modified = modifiedOf(id);
            String version = versionOf(id);
            JsonNode before = ok(Http.as(bearer(carol)).get(MEDIA + "/" + id + "?context=edit"));
            JsonNode unchanged = ok(postProcess(carol, id, ACTION));
            assertThat(unchanged.get("media_details")).as(name).isEqualTo(before.get("media_details"));
            assertThat(unchanged.get("modified_gmt")).as(name).isEqualTo(before.get("modified_gmt"));
            assertThat(modifiedOf(id)).as(name).isEqualTo(modified);
            assertThat(versionOf(id)).as(name).isEqualTo(version);
        }

        // a form body is read as the JSON one
        long form = sizeless(carol);
        JsonNode formAnswer = ok(Http.as(bearer(carol))
                .contentType("application/x-www-form-urlencoded")
                .body("action=create-image-subsizes")
                .post(MEDIA + "/" + form + "/post-process?context=view"));
        assertThat(fieldNames(formAnswer.at("/media_details/sizes"))).containsExactlyElementsOf(withFull(SIZES));
        assertThat(formAnswer.has("permalink_template")).isTrue();
        // _fields applies
        long fields = sizeless(carol);
        JsonNode selected = ok(Http.as(bearer(carol))
                .contentType("application/json")
                .body(ACTION)
                .post(MEDIA + "/" + fields + "/post-process?_fields=id,missing_image_sizes"));
        assertThat(fieldNames(selected)).containsExactly("id", "missing_image_sizes");
        assertThat(selected.get("missing_image_sizes").size()).isZero();
        // a pending attachment, stored without any metadata yet
        long pending = pending(carol);
        assertThat(processing(pending)).isEqualTo("pending");
        JsonNode completed = ok(postProcess(carol, pending, ACTION));
        assertThat(fieldNames(completed.at("/media_details/sizes"))).containsExactlyElementsOf(withFull(SIZES));
        assertThat(completed.at("/media_details/width").intValue()).isEqualTo(1200);
        assertThat(processing(pending)).isEqualTo("done");
    }

    @Test
    void theCrashedUploadIsCompletedByPostProcess() {
        String name = "crash" + suffix();
        processor.arm();
        Response crashed;
        try {
            crashed = Http.as(bearer(carol))
                    .multiPart("file", name + ".jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")
                    .post(MEDIA);
        } finally {
            processor.disarm();
        }
        assertThat(crashed.statusCode()).as(crashed.asString()).isEqualTo(500);
        long id = Long.parseLong(crashed.header("X-WP-Upload-Attachment-ID"));
        assertThat(processing(id)).isEqualTo("partial");
        JsonNode completed = ok(postProcess(carol, id, ACTION));
        assertThat(fieldNames(completed.at("/media_details/sizes")))
                .containsExactlyInAnyOrderElementsOf(withFull(SIZES));
        assertThat(completed.get("missing_image_sizes").size()).isZero();
        assertThat(processing(id)).isEqualTo("done");
    }

    @Test
    void aCrashWhileProcessingStillAnswers200() {
        long id = sizeless(carol);
        processor.arm();
        Response answer;
        try {
            answer = postProcess(carol, id, ACTION);
        } finally {
            processor.disarm();
        }
        // logged and ignored: the attachment as the crash left it (the first size saved), for another call to finish
        JsonNode partial = ok(answer);
        assertThat(answer.contentType()).startsWith("application/json");
        assertThat(partial.at("/media_details/sizes").size()).isEqualTo(2);
        assertThat(partial.get("missing_image_sizes").size()).isEqualTo(3);
        JsonNode finished = ok(postProcess(carol, id, ACTION));
        assertThat(finished.get("missing_image_sizes").size()).isZero();
    }

    @Test
    void fivePostProcessCallsAtOnceAgree() throws Exception {
        long id = pending(carol);
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> postProcess(carol, id, ACTION));
        }
        List<Object> results = Races.run(calls);
        List<JsonNode> sizes = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(Response.class);
            JsonNode answer = ok((Response) result);
            sizes.add(answer.at("/media_details/sizes"));
        }
        JsonNode later = ok(Http.as(bearer(carol)).get(MEDIA + "/" + id + "?context=edit"));
        assertThat(fieldNames(later.at("/media_details/sizes"))).containsExactlyInAnyOrderElementsOf(withFull(SIZES));
        assertThat(sizes).allSatisfy(each -> assertThat(each).isEqualTo(later.at("/media_details/sizes")));
        // no size file left over: the folder holds the main file and one file per size
        String main = later.at("/media_details/file").asText();
        Path folder = Path.of(fsDirectory()).resolve(main).getParent();
        String base = Path.of(main).getFileName().toString();
        String stem = base.substring(0, base.lastIndexOf('.'));
        List<String> expected = new ArrayList<>();
        expected.add(base);
        later.at("/media_details/sizes").forEach(size -> {
            if (!size.get("file").asText().equals(base)) {
                expected.add(size.get("file").asText());
            }
        });
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(file -> file.getFileName().toString())
                            .filter(file -> file.startsWith(stem + ".") || file.startsWith(stem + "-")))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // ---- helpers

    /** A 1200x800 JPEG uploaded by {@code user} through REST without its sizes. */
    private static long sizeless(UserId user) {
        Response uploaded = Http.as(bearer(user))
                .multiPart("file", "s" + suffix() + ".jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")
                .multiPart("generate_sub_sizes", "false")
                .post(MEDIA);
        assertThat(uploaded.statusCode()).as(uploaded.asString()).isEqualTo(201);
        JsonNode body = json(uploaded.asString());
        assertThat(body.at("/media_details/sizes").toString()).isEqualTo("{}");
        return body.get("id").longValue();
    }

    private static long uploadedRaw(UserId user, String name, String type, byte[] bytes) {
        Response uploaded = Http.as(bearer(user))
                .contentType(type)
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .body(bytes)
                .post(MEDIA);
        assertThat(uploaded.statusCode()).as(uploaded.asString()).isEqualTo(201);
        return json(uploaded.asString()).get("id").longValue();
    }

    /** A 1200x800 JPEG stored by {@code user} through the media module and not processed: pending, no metadata. */
    private static long pending(UserId user) {
        Path file;
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-fixtures"));
            file = Files.write(dir.resolve(UUID.randomUUID().toString()), MediaFixtures.jpeg(1200, 800));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Upload upload = Upload.builder("p" + suffix() + ".jpg", file)
                .declaredType("image/jpeg")
                .build();
        try {
            MediaSnapshot stored = Arc.container()
                    .instance(SiteScope.class)
                    .get()
                    .call(TestData.MAIN, () -> {
                        Arc.container()
                                .instance(CurrentIdentityAssociation.class)
                                .get()
                                .setIdentity(TestBearer.identity(user));
                        Media media = Arc.container()
                                .instance(MediaFixturesForRest.Beans.class)
                                .get()
                                .media;
                        return QuarkusTransaction.suspendingExisting().call(() -> media.store(upload));
                    });
            return stored.post().id().value();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Response postProcess(UserId user, long id, String body) {
        return Http.as(user == null ? null : bearer(user))
                .contentType("application/json")
                .body(body)
                .post(MEDIA + "/" + id + "/post-process");
    }

    private static List<String> withFull(List<String> sizes) {
        List<String> names = new ArrayList<>(sizes);
        names.add("full");
        return names;
    }

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static String processing(long id) {
        return Jdbc.strings(data, "select processing from qp_media where post_id = ?", id)
                .get(0);
    }

    private static String sizes(long id) {
        return Jdbc.strings(data, "select sizes from qp_media where post_id = ?", id)
                .get(0)
                .replace(" ", "");
    }

    private static String versionOf(long id) {
        return Jdbc.strings(data, "select version from qp_media where post_id = ?", id)
                .get(0);
    }

    private static String modifiedOf(long id) {
        return Jdbc.strings(data, "select modified_at from qp_post where id = ?", id)
                .get(0);
    }

    private static String fsDirectory() {
        return ConfigProvider.getConfig().getValue("quarkpress.media.fs.directory", String.class);
    }

    private static JsonNode ok(Response answer) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return json(answer.asString());
    }

    private static JsonNode error(Response answer, int status, String code, String message) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(status);
        JsonNode body = json(answer.asString());
        assertThat(body.get("code").asText()).as(answer.asString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.at("/data/status").intValue()).isEqualTo(status);
        return body;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
