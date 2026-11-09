package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The refusals of {@code POST /wp/v2/media} before anything is stored, as WordPress answers them: the create
 * permission in WordPress's order (and the collection's {@code Allow}, which follows from it), the parent's type, the
 * multipart body's {@code file} part and the raw body's headers; no temporary file outlives a refusal, a stalled body
 * or an aborted multipart upload, and a connection whose body was left unread serves the next request. Each test
 * uploads to a site of its own, where bob is an editor, carol an author, dave a contributor and erin a subscriber;
 * alice is a super admin.
 */
class MediaUploadRefusalsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaUploadRefusalsDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .overrideConfigKey("quarkus.http.read-timeout", "2s")
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-refusals");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final String BOUNDARY = "qp-upload-boundary-5e2";

    private static final String CANNOT_CREATE = "Sorry, you are not allowed to create posts as this user.";

    private static final String CANNOT_EDIT = "Sorry, you are not allowed to upload media to this post.";

    private static final String INVALID_DISPOSITION = "Invalid Content-Disposition supplied. Content-Disposition"
            + " needs to be formatted as `attachment; filename=\"image.png\"` or similar.";

    private static final String UNSUPPORTED_IMAGE = "The web server cannot generate responsive image sizes for this"
            + " image. Convert it to JPEG or PNG before uploading.";

    private static final String NO_ROUTE = "No route was found matching the URL and request method.";

    static AgroalDataSource data;

    @Inject
    NetworkOptions network;

    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId dave;
    static UserId erin;

    @BeforeAll
    static void users() {
        data = Jdbc.dataSource();
        alice = TestData.profile(data, "administrator");
        TestData.superAdmin(data, alice);
        bob = TestData.profile(data, "editor");
        carol = TestData.profile(data, "author");
        dave = TestData.profile(data, "contributor");
        erin = TestData.profile(data, "subscriber");
    }

    @Test
    void thePermissionChainInOrder() {
        Site site = site();
        PostId draft = site.post(bob, "draft");
        site.refused(site.jpeg(dave), 403, "rest_cannot_create", CANNOT_CREATE);
        site.refused(site.jpeg(erin), 403, "rest_cannot_create", CANNOT_CREATE);
        site.refused(site.jpeg(null), 401, "rest_cannot_create", CANNOT_CREATE);
        site.refused(site.jpeg(carol, "post", draft.value()), 403, "rest_cannot_edit", CANNOT_EDIT);
        site.refused(site.jpeg(alice, "post", 999_999), 403, "rest_cannot_edit", CANNOT_EDIT);
        Reply sticky = site.refused(
                site.jpeg(carol, "sticky", "1"),
                403,
                "rest_cannot_assign_sticky",
                "Sorry, you are not allowed to make posts sticky.");
        assertThat(sticky.header("allow")).isEqualTo("GET");
        site.refused(
                site.jpeg(carol, "author", bob.value()),
                403,
                "rest_cannot_edit_others",
                "Sorry, you are not allowed to create posts as this user.");
        site.refused(site.jpeg(carol, "id", 5), 400, "rest_post_exists", "Cannot create existing post.");

        // the order: id, then author, then sticky, then create_posts, then the parent
        site.refused(site.jpeg(dave, "id", 5, "sticky", "1"), 400, "rest_post_exists", "Cannot create existing post.");
        site.refused(
                site.jpeg(erin, "author", bob.value(), "post", draft.value()),
                403,
                "rest_cannot_edit_others",
                "Sorry, you are not allowed to create posts as this user.");
        site.refused(
                site.jpeg(dave, "sticky", "1"),
                403,
                "rest_cannot_assign_sticky",
                "Sorry, you are not allowed to make posts sticky.");
        site.refused(
                site.jpeg(null, "sticky", "true", "post", draft.value()),
                401,
                "rest_cannot_assign_sticky",
                "Sorry, you are not allowed to make posts sticky.");
        site.refused(site.jpeg(dave, "post", 999_999), 403, "rest_cannot_create", CANNOT_CREATE);
        // a sticky sent as JSON counts as well, a falsy one and the caller's own id do not
        site.refused(
                site.json(carol, "", "{\"sticky\":true}"),
                403,
                "rest_cannot_assign_sticky",
                "Sorry, you are not allowed to make posts sticky.");
        site.passes(site.jpeg(carol, "sticky", "0", "author", carol.value(), "id", 0));
        // bob, an editor, may make it sticky and name another author
        site.passes(site.jpeg(bob, "sticky", "1", "author", carol.value()));
    }

    @Test
    void anUnsupportedDeclaredImageIsRefusedWithAllowGet() {
        Site site = site();
        Reply refused = site.refused(
                site.multipart(carol, part("file", "a.webp", "image/webp", MediaFixtures.png(8, 8))),
                400,
                "rest_upload_image_type_not_supported",
                UNSUPPORTED_IMAGE);
        assertThat(refused.header("allow")).isEqualTo("GET");
        // the refusal comes after the parent's check
        site.refused(
                site.multipart(
                        carol, part("file", "a.webp", "image/webp", MediaFixtures.png(8, 8)), text("post", "999999")),
                403,
                "rest_cannot_edit",
                CANNOT_EDIT);
        // without sizes to make, and for an image the server processes, the check passes
        site.passes(site.multipart(
                carol,
                part("file", "a.webp", "image/webp", MediaFixtures.png(8, 8)),
                text("generate_sub_sizes", "false")));
        site.passes(site.multipart(carol, part("file", "a.png", "image/png", MediaFixtures.png(8, 8))));
        // only the part named file is checked, and never a raw body
        site.refused(
                site.multipart(carol, part("upload", "a.webp", "image/webp", MediaFixtures.png(8, 8))),
                500,
                "rest_upload_unknown_error",
                "Specified file failed upload test.");
        site.passes(site.raw(carol, "", "image/webp", List.of("attachment; filename=a.webp"), MediaFixtures.png(8, 8)));
    }

    @Test
    @Disabled("Task 13")
    void anUnsupportedDeclaredImageIsStoredWithoutSizes() {
        Site site = site();
        Reply stored = site.multipart(
                carol,
                part("file", "a.webp", "image/webp", MediaFixtures.png(8, 8)),
                text("generate_sub_sizes", "false"));
        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
    }

    @Test
    void invalidParentTypeComesAfterThePermission() {
        Site site = site();
        PostId published = site.post(bob, "publish");
        PostId revision = PostFixtures.builder(site.id())
                .type("revision")
                .status("inherit")
                .author(bob.value())
                .parent(published)
                .title("revision " + suffix())
                .insert(data);
        long attachment = MediaFixturesForRest.upload(site.id(), bob, "parent.jpg", false)
                .post()
                .id()
                .value();
        String invalid =
                "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parent type.\",\"data\":" + "{\"status\":400}}";
        for (long parent : List.of(revision.value(), attachment)) {
            Reply multipart = site.jpeg(bob, "post", parent);
            assertThat(multipart.status()).as(multipart.text()).isEqualTo(400);
            assertThat(multipart.body()).isEqualTo(invalid);
            assertThat(multipart.header("allow")).isEqualTo("GET, POST");
            Reply raw = site.raw(
                    bob,
                    "?post=" + parent,
                    "image/png",
                    List.of("attachment; filename=a.png"),
                    MediaFixtures.png(8, 8));
            assertThat(raw.status()).as(raw.text()).isEqualTo(400);
            assertThat(raw.body()).isEqualTo(invalid);
        }
        // the parent's type is checked before the body: a raw body without headers still answers it
        Reply bare = site.raw(bob, "?post=" + revision.value(), null, List.of(), new byte[0]);
        assertThat(bare.body()).isEqualTo(invalid);
        // the permission comes first: one who may not edit the revision's post is refused as for any post
        site.refused(site.jpeg(carol, "post", revision.value()), 403, "rest_cannot_edit", CANNOT_EDIT);
        site.refused(site.jpeg(erin, "post", revision.value()), 403, "rest_cannot_create", CANNOT_CREATE);
    }

    @Test
    void multipartWithoutTheFilePart() {
        Site site = site();
        PostId published = site.post(carol, "publish");
        site.refused(
                site.multipart(carol, text("post", Long.toString(published.value()))),
                400,
                "rest_upload_no_data",
                "No data supplied.");
        site.refused(
                site.multipart(carol, part("upload", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))),
                500,
                "rest_upload_unknown_error",
                "Specified file failed upload test.");
        Reply list = site.multipart(carol, part("file[]", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8)));
        assertThat(list.status()).as(list.text()).isEqualTo(500);
        assertThat(list.body())
                .isEqualTo("{\"code\":\"rest_upload_unknown_error\",\"message\":[0],\"data\":{\"status\":500}}");
        assertThat(list.header("allow")).isEqualTo("GET, POST");
        Reply keyed = site.multipart(carol, part("file[a]", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8)));
        assertThat(keyed.status()).as(keyed.text()).isEqualTo(500);
        assertThat(keyed.body())
                .isEqualTo("{\"code\":\"rest_upload_unknown_error\",\"message\":{\"a\":0},\"data\":{\"status\":500}}");
        site.refused(
                site.multipart(carol, part("file", "", "application/octet-stream", new byte[0])),
                500,
                "rest_upload_unknown_error",
                "No file was uploaded.");
        // a declared image type of a part with no file name is not checked
        site.refused(
                site.multipart(carol, part("file", "", "image/webp", new byte[0])),
                500,
                "rest_upload_unknown_error",
                "No file was uploaded.");
    }

    @Test
    void unreadableMultipartBodies() {
        Site site = site();
        // without a boundary PHP reads no part: the body stays the raw body
        Reply noBoundary = site.send(
                carol,
                "POST",
                MEDIA,
                List.of("Content-Type: multipart/form-data"),
                "abc".getBytes(StandardCharsets.UTF_8));
        assertError(noBoundary, 400, "rest_upload_no_content_disposition", "No Content-Disposition supplied.");
        // a boundary that never comes: no part and no raw body
        Reply missingBoundary = site.send(
                carol,
                "POST",
                MEDIA,
                List.of("Content-Type: multipart/form-data; boundary=never-there"),
                "abc".getBytes(StandardCharsets.UTF_8));
        assertError(missingBoundary, 400, "rest_upload_no_data", "No data supplied.");
        assertThat(missingBoundary.header("allow")).isEqualTo("GET, POST");
        // a body cut before its closing boundary
        Reply unclosed = site.send(
                carol,
                "POST",
                MEDIA,
                multipartHead(List.of()),
                part("file", "a.png", "image/png", MediaFixtures.png(8, 8)));
        assertError(unclosed, 400, "rest_upload_no_data", "No data supplied.");
    }

    @Test
    void contentMd5IsCheckedOnTheFile() {
        Site site = site();
        byte[] jpeg = MediaFixtures.jpeg(8, 8);
        String hex = md5(jpeg);
        String mismatch = "Content hash did not match expected.";
        for (String wrong : List.of(hex.toUpperCase(Locale.ROOT), "", md5("x".getBytes(StandardCharsets.UTF_8)))) {
            site.refused(
                    site.send(
                            carol,
                            "POST",
                            MEDIA,
                            multipartHead(List.of("Content-MD5: " + wrong)),
                            body(part("file", "a.jpg", "image/jpeg", jpeg))),
                    412,
                    "rest_upload_hash_mismatch",
                    mismatch);
            site.refused(
                    site.raw(
                            carol,
                            "",
                            "image/jpeg",
                            List.of("attachment; filename=a.jpg"),
                            jpeg,
                            "Content-MD5: " + wrong),
                    412,
                    "rest_upload_hash_mismatch",
                    mismatch);
        }
        site.passes(site.send(
                carol,
                "POST",
                MEDIA,
                multipartHead(List.of("Content-MD5: " + hex)),
                body(part("file", "a.jpg", "image/jpeg", jpeg))));
        site.passes(
                site.raw(carol, "", "image/jpeg", List.of("attachment; filename=a.jpg"), jpeg, "Content-MD5: " + hex));
    }

    @Test
    void rawBodyChecksInWordPresssOrder() {
        Site site = site();
        byte[] png = MediaFixtures.png(8, 8);
        site.refused(
                site.raw(carol, "", null, List.of(), new byte[0]), 400, "rest_upload_no_data", "No data supplied.");
        site.refused(
                site.raw(carol, "", "image/png", List.of("attachment; filename=a.png"), new byte[0]),
                400,
                "rest_upload_no_data",
                "No data supplied.");
        site.refused(
                site.raw(carol, "", null, List.of("attachment; filename=a.png"), png),
                400,
                "rest_upload_no_content_type",
                "No Content-Type supplied.");
        site.refused(
                site.raw(carol, "", "image/png", List.of(), png),
                400,
                "rest_upload_no_content_disposition",
                "No Content-Disposition supplied.");
        for (String disposition : List.of(
                "attachment",
                "attachment; FILENAME=\"a.png\"",
                "attachment; filename*=UTF-8''%C3%A9t%C3%A9.png",
                "attachment; filename=\"\"")) {
            site.refused(
                    site.raw(carol, "", "image/png", List.of(disposition), png),
                    400,
                    "rest_upload_invalid_disposition",
                    INVALID_DISPOSITION);
        }
        // the last filename piece decides, even an empty one; "0" is empty to PHP, the body "0" too (WordPress, e05)
        for (List<String> dispositions : List.of(
                List.of("attachment; filename=a.png; filename="),
                List.of("attachment; filename=a.png", "attachment; filename=\"\""),
                List.of("attachment; filename=0"))) {
            site.refused(
                    site.raw(carol, "", "image/png", dispositions, png),
                    400,
                    "rest_upload_invalid_disposition",
                    INVALID_DISPOSITION);
        }
        site.refused(
                site.raw(
                        carol,
                        "",
                        "image/png",
                        List.of("attachment; filename=a.png"),
                        "0".getBytes(StandardCharsets.UTF_8)),
                400,
                "rest_upload_no_data",
                "No data supplied.");
        // an empty header line is a header all the same, without a file name
        site.refused(
                site.raw(carol, "", "image/png", List.of(""), png),
                400,
                "rest_upload_invalid_disposition",
                INVALID_DISPOSITION);
        // an empty type line is a type all the same: the upload passes the header checks
        site.passes(site.raw(carol, "", null, List.of("attachment; filename=a.png"), png, "Content-Type: "));
        // a JSON body is a raw body too
        site.refused(
                site.json(carol, "", "{\"title\":\"x\"}"),
                400,
                "rest_upload_no_content_disposition",
                "No Content-Disposition supplied.");
        // the permission comes first
        site.refused(site.raw(erin, "", "image/png", List.of(), png), 403, "rest_cannot_create", CANNOT_CREATE);
    }

    @Test
    void aJsonUrlBodyIsARawUploadOfTheJson() {
        Site site = site();
        for (String url : List.of("https://example.org/x.jpg", "http://127.0.0.1/x.jpg")) {
            site.refused(
                    site.json(carol, "", "{\"url\":\"" + url + "\"}"),
                    400,
                    "rest_upload_no_content_disposition",
                    "No Content-Disposition supplied.");
        }
        // in the query string as well: an undeclared parameter
        site.refused(
                site.json(carol, "?url=http://127.0.0.1/x.jpg", "{}"),
                400,
                "rest_upload_no_content_disposition",
                "No Content-Disposition supplied.");
        site.passes(site.jpeg(carol, "url", "https://example.org/x.jpg"));
    }

    @Test
    @Disabled("Task 13")
    void aMultipartUploadWithAUrlFieldStoresItsFile() {
        Site site = site();
        Reply stored = site.jpeg(carol, "url", "https://example.org/x.jpg");
        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
        assertThat(json(stored).get("mime_type").asText()).isEqualTo("image/jpeg");
    }

    @Test
    @Disabled("Task 13")
    void pathsInTheNameNeverLeaveTheFolder() throws IOException {
        Site site = site();
        Path root = Path.of(fsDirectory());
        Set<Path> before = files(root);
        Reply stored = site.raw(
                carol, "", "image/png", List.of("attachment; filename=\"../../x.png\""), MediaFixtures.png(8, 8));
        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
        assertThat(json(stored).at("/media_details/file").asText()).matches("\\d{4}/\\d{2}/x\\.png");
        site.refused(
                site.raw(
                        carol,
                        "",
                        "image/png",
                        List.of("attachment; filename=\"../../wp-config.php\""),
                        MediaFixtures.png(8, 8)),
                500,
                "rest_upload_sideload_error",
                "Sorry, you are not allowed to upload this file type.");
        Set<Path> added = files(root);
        added.removeAll(before);
        assertThat(added)
                .allSatisfy(file ->
                        assertThat(root.relativize(file).toString()).matches("(sites/\\d+/)?\\d{4}/\\d{2}/[^/]+"));
    }

    @Test
    void anotherSitesParentIsRefused() {
        Site site = site();
        Site other = site();
        PostId elsewhere = other.post(bob, "publish");
        site.refused(site.jpeg(alice, "post", elsewhere.value()), 403, "rest_cannot_edit", CANNOT_EDIT);
        site.refused(site.jpeg(bob, "post", elsewhere.value()), 403, "rest_cannot_edit", CANNOT_EDIT);
    }

    @Test
    void theCollectionsAllowFollowsTheCreateCheck() {
        Site site = site();
        PostId draft = site.post(bob, "draft");
        assertThat(site.get(null, "").header("allow")).isEqualTo("GET");
        assertThat(site.get(erin, "").header("allow")).isEqualTo("GET");
        assertThat(site.get(dave, "").header("allow")).isEqualTo("GET");
        assertThat(site.get(carol, "").header("allow")).isEqualTo("GET, POST");
        assertThat(site.get(bob, "").header("allow")).isEqualTo("GET, POST");
        // the create check reads the request's post
        assertThat(site.get(carol, "?post=" + draft.value()).header("allow")).isEqualTo("GET");
        assertThat(site.get(bob, "?post=" + draft.value()).header("allow")).isEqualTo("GET, POST");
        // and its sticky
        assertThat(site.get(carol, "?sticky=1").header("allow")).isEqualTo("GET");
        // a site whose media feature is off has no media route at all
        TestData.siteOption(data, site.id(), "qp_features", "{\"media\": false}");
        site.refused(site.jpeg(carol), 404, "rest_no_route", NO_ROUTE);
        TestData.siteOption(data, site.id(), "qp_features", "{\"media\": true}");
    }

    @Test
    void noTemporaryFileOutlivesARefusal() throws IOException {
        Site site = site();
        Path temporary = Path.of(fsDirectory(), ".tmp");
        Set<Path> before = files(temporary);
        byte[] png = MediaFixtures.png(8, 8);
        site.refused(
                site.raw(carol, "", "image/png", List.of("attachment; filename=a.png"), png, "Content-MD5: 0"),
                412,
                "rest_upload_hash_mismatch",
                "Content hash did not match expected.");
        site.refused(
                site.raw(carol, "", "image/png", List.of("attachment"), png),
                400,
                "rest_upload_invalid_disposition",
                INVALID_DISPOSITION);
        site.refused(
                site.raw(carol, "", "image/png", List.of(), png),
                400,
                "rest_upload_no_content_disposition",
                "No Content-Disposition supplied.");
        site.refused(site.raw(dave, "", "image/png", List.of(), png), 403, "rest_cannot_create", CANNOT_CREATE);
        // a body that passes every check (stored from Task 13 on) leaves no spool either
        site.raw(carol, "", "image/png", List.of("attachment; filename=a.png"), png);
        assertThat(files(temporary)).isEqualTo(before);
    }

    @Test
    void aStalledRawBodyLeavesNoSpool() throws Exception {
        Site site = site();
        Path temporary = Path.of(fsDirectory(), ".tmp");
        Set<Path> before = files(temporary);
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + MEDIA + " HTTP/1.1\r\nHost: " + site.host() + "\r\nAuthorization: Bearer test-"
                            + carol.value() + "\r\nContent-Type: image/png\r\nContent-Disposition: attachment;"
                            + " filename=a.png\r\nContent-Length: " + (8 * 1024 * 1024) + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 7);
            for (int i = 0; i < 16; i++) {
                out.write(chunk);
            }
            out.flush();
            // the spool is written while the body arrives
            assertThat(poll(() -> !newFiles(temporary, before).isEmpty(), 5_000))
                    .as("a spool file appears")
                    .isTrue();
            // the body then stops: once the read times out, the spool is gone
            assertThat(poll(() -> newFiles(temporary, before).isEmpty(), 8_000))
                    .as("the spool is deleted")
                    .isTrue();
        }
    }

    @Test
    void aConnectionIsReusedAfterAnUnreadBody() throws IOException {
        Site site = site();
        byte[] large = new byte[1024 * 1024];
        Arrays.fill(large, (byte) 9);
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            // refused before the body is read: by the permission, then by the headers after one byte
            write(out, site.host(), null, "Content-Type: image/png\r\n", large);
            assertThat(readReply(in).status()).isEqualTo(401);
            write(out, site.host(), carol, "Content-Type: image/png\r\n", large);
            Reply headers = readReply(in);
            assertThat(headers.status()).as(headers.text()).isEqualTo(400);
            assertThat(json(headers).get("code").asText()).isEqualTo("rest_upload_no_content_disposition");
            out.write(("GET " + MEDIA + "?_fields=id HTTP/1.1\r\nHost: " + site.host() + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Reply next = readReply(in);
            assertThat(next.status()).as(next.text()).isEqualTo(200);
            assertThat(next.body()).isEqualTo("[]");
        }
    }

    /**
     * Quarkus writes a multipart file part to its uploads directory while the body arrives; a client that goes away
     * mid-part leaves no file behind.
     */
    @Test
    void multipartFilesAreGoneWhenTheClientAborts() throws Exception {
        Site site = site();
        Path uploads = uploadsDirectory();
        Set<Path> before = files(uploads);
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + MEDIA + " HTTP/1.1\r\nHost: " + site.host() + "\r\nAuthorization: Bearer test-"
                            + carol.value() + "\r\nContent-Type: multipart/form-data; boundary=" + BOUNDARY
                            + "\r\nContent-Length: " + (8 * 1024 * 1024) + "\r\n\r\n--" + BOUNDARY
                            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
                            + "Content-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 7);
            for (int i = 0; i < 16; i++) {
                out.write(chunk);
            }
            out.flush();
            assertThat(poll(() -> !newFiles(uploads, before).isEmpty(), 5_000))
                    .as("the part is written while it arrives")
                    .isTrue();
        }
        // the client went away mid-part
        assertThat(poll(() -> newFiles(uploads, before).isEmpty(), 5_000))
                .as("the part's file is deleted")
                .isTrue();
    }

    @Test
    void anAbortedMultipartRequestIsNotServed() throws Exception {
        Site site = site();
        PostId post = site.post(bob, "publish");
        String title = Jdbc.strings(data, "select title from qp_post where id = ?", post.value())
                .get(0);
        // the client goes away halfway through its body, at once or after the server's read timeout (2 s here)
        for (long stall : List.of(500L, 4_000L)) {
            try (Socket socket = new Socket("localhost", RestAssured.port)) {
                OutputStream out = socket.getOutputStream();
                byte[] part = text("content", "x".repeat(4096));
                out.write(("POST /wp-json/wp/v2/posts/" + post.value() + "?title=changed HTTP/1.1\r\nHost: "
                                + site.host() + "\r\nAuthorization: Bearer test-" + bob.value()
                                + "\r\nContent-Type: multipart/form-data; boundary=" + BOUNDARY
                                + "\r\nContent-Length: " + 2 * part.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(part);
                out.flush();
                Thread.sleep(stall);
            }
            Thread.sleep(1_000);
            // nothing it asked for is done
            assertThat(Jdbc.strings(data, "select title from qp_post where id = ?", post.value()))
                    .as("after a stall of " + stall + " ms")
                    .containsExactly(title);
        }
        // the same request sent whole is served
        Reply whole = site.send(
                bob,
                "POST",
                "/wp-json/wp/v2/posts/" + post.value() + "?title=changed",
                multipartHead(List.of()),
                body(text("content", "x")));
        assertThat(whole.status()).as(whole.text()).isEqualTo(200);
        assertThat(Jdbc.strings(data, "select title from qp_post where id = ?", post.value()))
                .containsExactly("changed");
    }

    @Test
    @Disabled("Task 13")
    void networkLimitsAnswer400() {
        Site site = site();
        byte[] body = new byte[2 * 1024 * 1024];
        Arrays.fill(body, (byte) 'x');
        network.set("upload_space_check_disabled", MAPPER.getNodeFactory().textNode("0"));
        try {
            network.set("fileupload_maxk", MAPPER.getNodeFactory().textNode("1"));
            site.refused(
                    site.raw(carol, "", "image/png", List.of("attachment; filename=a.png"), body),
                    400,
                    "rest_upload_file_too_big",
                    "This file is too big. Files must be less than 1 KB in size.");
            network.set("fileupload_maxk", MAPPER.getNodeFactory().textNode("4000"));
            network.set("blog_upload_space", MAPPER.getNodeFactory().textNode("1"));
            Reply full = site.raw(carol, "", "image/png", List.of("attachment; filename=a.png"), body);
            assertThat(full.status()).as(full.text()).isEqualTo(400);
            assertThat(json(full).get("code").asText()).isEqualTo("rest_upload_limited_space");
        } finally {
            network.set("upload_space_check_disabled", MAPPER.getNodeFactory().textNode("1"));
            network.set("fileupload_maxk", MAPPER.getNodeFactory().textNode("1500"));
            network.set("blog_upload_space", MAPPER.getNodeFactory().textNode("100"));
        }
    }

    // ---- helpers

    /** A site of its own, with bob as editor, carol as author, dave as contributor and erin as subscriber. */
    private static Site site() {
        SiteId site = TestData.site(data);
        TestData.member(data, site, bob, "[\"editor\"]");
        TestData.member(data, site, carol, "[\"author\"]");
        TestData.member(data, site, dave, "[\"contributor\"]");
        TestData.member(data, site, erin, "[\"subscriber\"]");
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        return new Site(site, host);
    }

    private record Site(SiteId id, String host) {

        PostId post(UserId author, String status) {
            return PostFixtures.builder(id)
                    .type("post")
                    .status(status)
                    .author(author.value())
                    .title(status + " " + suffix())
                    .publishedAt(status.equals("draft") ? null : "2026-01-01T00:00:00Z")
                    .modifiedAt("2026-01-01T00:00:00Z")
                    .insert(data);
        }

        /** A multipart upload of an 8x8 JPEG (a type the server makes sizes for) with the given text fields. */
        Reply jpeg(UserId user, Object... fields) {
            List<byte[]> parts = new ArrayList<>();
            parts.add(part("file", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8)));
            for (int i = 0; i < fields.length; i += 2) {
                parts.add(text(fields[i].toString(), fields[i + 1].toString()));
            }
            return multipart(user, parts.toArray(byte[][]::new));
        }

        Reply multipart(UserId user, byte[]... parts) {
            return send(user, "POST", MEDIA, multipartHead(List.of()), body(parts));
        }

        Reply json(UserId user, String query, String json) {
            return send(
                    user,
                    "POST",
                    MEDIA + query,
                    List.of("Content-Type: application/json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }

        Reply raw(
                UserId user, String query, String contentType, List<String> dispositions, byte[] body, String... more) {
            List<String> headers = new ArrayList<>();
            if (contentType != null) {
                headers.add("Content-Type: " + contentType);
            }
            dispositions.forEach(value -> headers.add("Content-Disposition: " + value));
            headers.addAll(List.of(more));
            return send(user, "POST", MEDIA + query, headers, body);
        }

        Reply get(UserId user, String query) {
            Reply reply = send(user, "GET", MEDIA + query, List.of(), null);
            assertThat(reply.status()).as(reply.text()).isEqualTo(200);
            return reply;
        }

        Reply send(UserId user, String method, String target, List<String> headers, byte[] body) {
            StringBuilder head = new StringBuilder(
                    method + " " + target + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n");
            if (user != null) {
                head.append("Authorization: Bearer test-").append(user.value()).append("\r\n");
            }
            headers.forEach(header -> head.append(header).append("\r\n"));
            if (body != null) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            try (Socket socket = new Socket("localhost", RestAssured.port)) {
                socket.setSoTimeout(30_000);
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) {
                    try {
                        out.write(body);
                    } catch (IOException answeredEarly) {
                        // the server answered before reading the whole body
                    }
                }
                out.flush();
                return readReply(socket.getInputStream());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        Reply refused(Reply reply, int status, String code, String message) {
            assertError(reply, status, code, message);
            return reply;
        }

        /**
         * A request that passed every check before the attachment would be stored: until uploads are stored, it
         * answers as no route would, with POST allowed.
         */
        void passes(Reply reply) {
            assertError(reply, 404, "rest_no_route", NO_ROUTE);
            assertThat(reply.header("allow")).isEqualTo("GET, POST");
        }
    }

    /** An answer: status, headers by lower-case name, body. */
    record Reply(int status, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : String.join(", ", values);
        }

        String text() {
            return status + " " + headers + " " + body;
        }
    }

    private static void assertError(Reply reply, int status, String code, String message) {
        assertThat(reply.status()).as(reply.text()).isEqualTo(status);
        JsonNode body = json(reply);
        assertThat(body.get("code").asText()).as(reply.text()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.get("data").toString()).isEqualTo("{\"status\":" + status + "}");
    }

    private static void write(OutputStream out, String host, UserId user, String headers, byte[] body)
            throws IOException {
        out.write(("POST " + MEDIA + " HTTP/1.1\r\nHost: " + host + "\r\n"
                        + (user == null ? "" : "Authorization: Bearer test-" + user.value() + "\r\n") + headers
                        + "Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Reads one HTTP/1.1 answer, its body delimited by its length or chunked. */
    static Reply readReply(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("the connection ended before an answer: " + head);
            }
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : b == '\r' ? 1 : 0;
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].substring(9, 12));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.computeIfAbsent(
                                lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(lines[i].substring(colon + 1).trim());
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String length = headers.containsKey("content-length")
                ? headers.get("content-length").get(0)
                : null;
        if (length != null) {
            body.write(in.readNBytes(Integer.parseInt(length)));
        } else if ("chunked"
                .equalsIgnoreCase(
                        headers.getOrDefault("transfer-encoding", List.of("")).get(0))) {
            while (true) {
                StringBuilder size = new StringBuilder();
                int b;
                while ((b = in.read()) != '\n') {
                    if (b < 0) {
                        throw new IOException("chunked answer cut");
                    }
                    if (b != '\r') {
                        size.append((char) b);
                    }
                }
                int n = Integer.parseInt(size.toString().trim(), 16);
                if (n == 0) {
                    in.readNBytes(2);
                    break;
                }
                body.write(in.readNBytes(n));
                in.readNBytes(2);
            }
        } else {
            in.transferTo(body);
        }
        return new Reply(status, headers, body.toString(StandardCharsets.UTF_8));
    }

    private static List<String> multipartHead(List<String> more) {
        List<String> headers = new ArrayList<>();
        headers.add("Content-Type: multipart/form-data; boundary=" + BOUNDARY);
        headers.addAll(more);
        return headers;
    }

    private static byte[] body(byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            body.writeBytes(part);
        }
        body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return body.toByteArray();
    }

    private static byte[] part(String name, String fileName, String contentType, byte[] content) {
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        part.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                        + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        part.writeBytes(content);
        part.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return part.toByteArray();
    }

    private static byte[] text(String name, String value) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode json(Reply reply) {
        try {
            return MAPPER.readTree(reply.body());
        } catch (IOException failure) {
            throw new AssertionError(reply.text(), failure);
        }
    }

    /** Read from the running application: this class is loaded twice, so a static value would differ. */
    private static Path uploadsDirectory() {
        return Path.of(ConfigProvider.getConfig().getValue("quarkus.http.body.uploads-directory", String.class));
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

    private static Set<Path> newFiles(Path root, Set<Path> before) {
        try {
            Set<Path> now = files(root);
            now.removeAll(before);
            return now;
        } catch (IOException | UncheckedIOException vanishedWhileListing) {
            return Set.of(Path.of("listing failed"));
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds();
    }

    private static boolean poll(Condition condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.holds();
    }

    private static String md5(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
