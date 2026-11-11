package org.quarkpress.it.media;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * The media library end to end, with real Keycloak tokens: uploads through the test resource ({@code /it/media}),
 * stored by the filesystem backend and served by its route, images processed by ImageIO with TwelveMonkeys, on a
 * network whose main site is {@code localhost} at {@code /}. JVM mode here; {@code MediaIT} runs the same tests against
 * the packaged application, and against the native executable in CI, which is where the type detection, the
 * metadata readers and the image plugins can fail.
 *
 * <p>Each start of the application stores its files in a new, empty directory ({@link MediaFilesResource}), as each
 * gets a new database, so the file names are exact.
 */
@QuarkusTest
@WithTestResource(MediaFilesResource.class)
class MediaDbTest {

    private static final String TWELVEMONKEYS = "com.twelvemonkeys.imageio.plugins.";

    /** WordPress's six default sizes, all made from a 4000 by 3000 image. */
    private static final List<String> DEFAULT_SIZES =
            List.of("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    private RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        role("bob", "editor");
    }

    private long userId(String user) {
        return as(user).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    /** Gives {@code user} the role on the main site, as alice (a network administrator). */
    private void role(String user, String role) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(user), "role", role))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    private static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Uploads {@code bytes} as {@code user} to the site at {@code sitePath} ({@code /} for the main site). */
    private Response upload(
            String user, String sitePath, String name, String declaredType, byte[] bytes, Map<String, String> fields) {
        RequestSpecification request = as(user).multiPart("file", name, bytes, declaredType);
        fields.forEach(request::multiPart);
        return request.post(sitePath + "it/media");
    }

    /** Uploads on the main site and expects it stored: the answered attachment. */
    private JsonPath uploaded(String user, String name, String declaredType, byte[] bytes) {
        Response response = upload(user, "/", name, declaredType, bytes, Map.of());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        return response.jsonPath();
    }

    /** The path of a file URL, fetched from the application with the network's host. */
    private Response fetch(String url) {
        return as(null).get(URI.create(url).getRawPath());
    }

    @Test
    void aLargeJpegIsStoredScaledAndServed() throws IOException {
        JsonPath media = uploaded("bob", "IMG_1234.jpg", "image/jpeg", MediaFixtures.jpeg(4000, 3000));
        long id = media.getLong("id");
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("mimeType")).isEqualTo("image/jpeg");
        assertThat(media.getString("title")).isEqualTo("IMG_1234");
        assertThat(media.getString("attachedFile")).matches("[0-9]{4}/[0-9]{2}/IMG_1234-scaled\\.jpg");
        assertThat(media.getString("originalImage")).isEqualTo("IMG_1234.jpg");
        assertThat(media.getInt("width")).isEqualTo(2560);
        assertThat(media.getInt("height")).isEqualTo(1920);
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        assertThat(sizes).containsOnlyKeys(DEFAULT_SIZES);
        assertThat(sizes.get("thumbnail")).containsEntry("width", 150).containsEntry("height", 150);
        assertThat(sizes.get("medium")).containsEntry("width", 300).containsEntry("height", 225);
        assertThat(sizes.get("2048x2048")).containsEntry("width", 2048).containsEntry("height", 1536);
        Map<String, String> names = new LinkedHashMap<>();
        sizes.forEach((name, size) -> names.put(name, (String) size.get("file")));
        assertThat(names)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "IMG_1234-150x150.jpg",
                        "medium", "IMG_1234-300x225.jpg",
                        "medium_large", "IMG_1234-768x576.jpg",
                        "large", "IMG_1234-1024x768.jpg",
                        "1536x1536", "IMG_1234-1536x1152.jpg",
                        "2048x2048", "IMG_1234-2048x1536.jpg"));

        // each size's URL is the main file's folder and the size's file, as WordPress's uploads URLs are
        String mainUrl = media.getString("url");
        String folder = mainUrl.substring(0, mainUrl.lastIndexOf('/') + 1);
        sizes.forEach((name, size) -> assertThat(size.get("url")).as(name).isEqualTo(folder + size.get("file")));

        // every file through the static route: the main file and each size, decoding to the stated dimensions
        Map<String, int[]> files = new LinkedHashMap<>();
        files.put(media.getString("url"), new int[] {2560, 1920});
        sizes.forEach((name, size) -> files.put(
                (String) size.get("url"), new int[] {(Integer) size.get("width"), (Integer) size.get("height")}));
        for (Map.Entry<String, int[]> file : files.entrySet()) {
            Response served = fetch(file.getKey());
            assertThat(served.statusCode()).as(file.getKey()).isEqualTo(200);
            assertThat(served.contentType()).as(file.getKey()).isEqualTo("image/jpeg");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(served.asByteArray()));
            assertThat(new int[] {image.getWidth(), image.getHeight()})
                    .as(file.getKey())
                    .containsExactly(file.getValue());
        }

        // read back, found by type and author, and in WordPress's form
        as("bob").get("/it/media/" + id).then().statusCode(200);
        List<Long> found = as("bob")
                .queryParam("mime_type", "image/jpeg")
                .queryParam("author", userId("bob"))
                .get("/it/media")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", Long.class);
        assertThat(found).contains(id);
        JsonPath wordPress = as("bob")
                .get("/it/media/" + id + "/wordpress")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(wordPress.getString("post.post_mime_type")).isEqualTo("image/jpeg");
        assertThat(wordPress.getString("post.post_status")).isEqualTo("inherit");
        assertThat(wordPress.getString("meta._wp_attached_file")).isEqualTo(media.getString("attachedFile"));
        assertThat(wordPress.getInt("meta._wp_attachment_metadata.width")).isEqualTo(2560);
        assertThat(wordPress.getList("files", String.class))
                .contains(media.getString("originalImage"), file(media.getString("attachedFile")))
                .isSorted();
    }

    @Test
    void aSubdirectorySiteUsesItsPrefix() {
        String path = "/s-" + token() + "/";
        long site = as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", "Media " + token()))
                .post("/it/sites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
        Response response = upload("alice", path, "kite.jpg", "image/jpeg", MediaFixtures.jpeg(400, 300), Map.of());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        JsonPath media = response.jsonPath();
        assertThat(media.getString("storageKey")).matches("sites/" + site + "/[0-9]{4}/[0-9]{2}/kite\\.jpg");
        assertThat(media.getString("attachedFile")).matches("[0-9]{4}/[0-9]{2}/kite\\.jpg");
        String url = media.getString("url");
        assertThat(URI.create(url).getRawPath())
                .isEqualTo(path + "wp-content/uploads/" + media.getString("storageKey"));
        Response served = fetch(url);
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.contentType()).isEqualTo("image/jpeg");
        String thumbnail = media.getString("sizes.thumbnail.url");
        assertThat(URI.create(thumbnail).getRawPath()).startsWith(path + "wp-content/uploads/sites/" + site + "/");
        assertThat(fetch(thumbnail).statusCode()).isEqualTo(200);
        // the main site's library does not hold it
        as("alice").get("/it/media/" + media.getLong("id")).then().statusCode(404);
        as("alice").get(path + "it/media/" + media.getLong("id")).then().statusCode(200);
    }

    @Test
    void theNativeSensitivePathsWork() throws IOException {
        // ImageIO finds the TwelveMonkeys plugins
        List<String> readers = as(null).get("/it/media/readers")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList(".", String.class);
        assertThat(readers)
                .contains(TWELVEMONKEYS + "jpeg.JPEGImageReaderSpi", TWELVEMONKEYS + "webp.WebPImageReaderSpi");

        // Tika's detection, the processor's header read and the metadata reader
        JsonPath docx = inspect(MediaFixtures.zipOoxml(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"));
        assertThat(docx.getString("detected")).isEqualTo("application/zip");
        assertThat(docx.getMap("image")).isNull();
        JsonPath webp = inspect(MediaFixtures.webp());
        assertThat(webp.getString("detected")).isEqualTo("image/webp");
        assertThat(webp.getString("image.mimeType")).isEqualTo("image/webp");
        assertThat(webp.getInt("image.width")).isEqualTo(24);
        assertThat(webp.getInt("image.height")).isEqualTo(16);

        // a CMYK JPEG is decoded and cut into sizes
        JsonPath cmyk = uploaded("bob", "cmyk.jpg", "image/jpeg", cmykJpeg(320, 240));
        assertThat(cmyk.getString("processing")).isEqualTo("done");
        assertThat(cmyk.getMap("sizes")).containsOnlyKeys("thumbnail", "medium");
        BufferedImage medium = ImageIO.read(new ByteArrayInputStream(
                fetch(cmyk.getString("sizes.medium.url")).asByteArray()));
        assertThat(new int[] {medium.getWidth(), medium.getHeight()}).containsExactly(300, 225);

        // an IPTC caption becomes the excerpt
        JsonPath iptc = uploaded(
                "bob",
                "iptc.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(200, 150, JpegMeta.none().withIptc(120, "From IPTC")));
        assertThat(iptc.getString("caption")).isEqualTo("From IPTC");
        assertThat(iptc.getString("imageMeta.caption")).isEqualTo("From IPTC");

        // EXIF, read by metadata-extractor's reflectively built directories
        JsonPath exif = uploaded(
                "bob",
                "exif.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(
                        200, 150, JpegMeta.none().withExif("FNumber", "28/10").withExif("Model", "QuarkPress One")));
        assertThat(exif.getString("imageMeta.aperture")).isEqualTo("2.8");
        assertThat(exif.getString("imageMeta.camera")).isEqualTo("QuarkPress One");

        // a video's length and dimensions
        JsonPath video = uploaded("bob", "clip.mp4", "video/mp4", MediaFixtures.mp4(320, 240, 3));
        assertThat(video.getString("mimeType")).isEqualTo("video/mp4");
        assertThat(video.getInt("metadata.length")).isEqualTo(3);
        assertThat(video.getString("metadata.length_formatted")).isEqualTo("0:03");
        assertThat(video.getInt("metadata.width")).isEqualTo(320);
        assertThat(video.getInt("metadata.height")).isEqualTo(240);

        // the library's filters: by type, and by author
        long videoId = video.getLong("id");
        assertThat(ids(Map.of("mime_type", "image/jpeg")))
                .contains(exif.getLong("id"))
                .doesNotContain(videoId);
        assertThat(ids(Map.of("mime_type", "video"))).contains(videoId).doesNotContain(exif.getLong("id"));
        assertThat(ids(Map.of("author", userId("bob")))).contains(videoId, exif.getLong("id"));
        assertThat(ids(Map.of("author", userId("alice")))).doesNotContain(videoId, exif.getLong("id"));
    }

    @Test
    void theUploadFieldsProcessUpdateAndParentQueriesWork() {
        long first = post("Parent one");
        long second = post("Parent two");
        Response response = upload(
                "bob",
                "/",
                "fields.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(400, 300),
                orderedFields(
                        "title", "A given title",
                        "caption", "A given caption",
                        "description", "A given description",
                        "alt_text", "A given alt",
                        "post", Long.toString(first),
                        "generate_sub_sizes", "false"));
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        JsonPath media = response.jsonPath();
        long id = media.getLong("id");
        assertThat(media.getString("title")).isEqualTo("A given title");
        assertThat(media.getString("caption")).isEqualTo("A given caption");
        assertThat(media.getString("description")).isEqualTo("A given description");
        assertThat(media.getString("altText")).isEqualTo("A given alt");
        assertThat(media.getLong("parent")).isEqualTo(first);
        assertThat(media.getString("attachedFile")).matches("[0-9]{4}/[0-9]{2}/fields\\.jpg");
        assertThat(media.getMap("sizes")).isEmpty();
        assertThat(as("bob")
                        .get("/it/media/" + id + "/wordpress")
                        .jsonPath()
                        .getString("meta._wp_attachment_image_alt"))
                .isEqualTo("A given alt");

        // WordPress's post-process makes the sizes the upload left out
        JsonPath processed = as("bob")
                .post("/it/media/" + id + "/process")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(processed.getString("processing")).isEqualTo("done");
        assertThat(processed.getString("sizes.thumbnail.file")).isEqualTo("fields-150x150.jpg");
        assertThat(processed.getString("sizes.medium.file")).isEqualTo("fields-300x225.jpg");
        assertThat(processed.getMap("sizes")).containsOnlyKeys("thumbnail", "medium");
        for (String size : List.of("thumbnail", "medium")) {
            Response served = fetch(processed.getString("sizes." + size + ".url"));
            assertThat(served.statusCode()).as(size).isEqualTo(200);
            assertThat(served.contentType()).as(size).isEqualTo("image/jpeg");
        }

        // an update of the title, the alternative text and the parent
        JsonPath updated = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "A new title", "alt_text", "A new alt", "post", second))
                .put("/it/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(updated.getString("title")).isEqualTo("A new title");
        assertThat(updated.getString("altText")).isEqualTo("A new alt");
        assertThat(updated.getLong("parent")).isEqualTo(second);
        JsonPath read = as("bob")
                .get("/it/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(read.getString("title")).isEqualTo("A new title");
        JsonPath wordPress = as("bob").get("/it/media/" + id + "/wordpress").jsonPath();
        assertThat(wordPress.getString("post.post_title")).isEqualTo("A new title");
        assertThat(wordPress.getLong("post.post_parent")).isEqualTo(second);
        assertThat(wordPress.getString("meta._wp_attachment_image_alt")).isEqualTo("A new alt");

        // found under its new parent only, and no longer among the unattached
        assertThat(ids(Map.of("parent", second))).containsExactly(id);
        assertThat(ids(Map.of("parent", first))).doesNotContain(id);
        assertThat(ids(Map.of("parent", 0))).doesNotContain(id);
        long loose = uploaded("bob", "loose.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .getLong("id");
        assertThat(ids(Map.of("parent", 0))).contains(loose);
    }

    @Test
    void anAdobeRgbJpegKeepsItsProfileInItsSizes() throws IOException {
        byte[] adobeRgb = clayRgb();
        JsonPath media = uploaded(
                "bob",
                "adobe-rgb.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(400, 300, JpegMeta.none().withIcc(adobeRgb)));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getMap("sizes")).containsOnlyKeys("thumbnail", "medium");
        byte[] profile = ICC_Profile.getInstance(adobeRgb).getData();
        for (String size : List.of("thumbnail", "medium")) {
            Response served = fetch(media.getString("sizes." + size + ".url"));
            assertThat(served.statusCode()).isEqualTo(200);
            byte[] bytes = served.asByteArray();
            assertThat(ImageIO.read(new ByteArrayInputStream(bytes))).as(size).isNotNull();
            assertThat(jpegProfile(bytes)).as(size).isEqualTo(profile);
        }
    }

    @Test
    void refusalsHaveWordPresssCodes() {
        Response webp = upload("bob", "/", "x.webp", "image/webp", MediaFixtures.webp(), Map.of());
        assertThat(webp.statusCode()).as(webp.asString()).isEqualTo(400);
        assertThat(webp.jsonPath().getString("code")).isEqualTo("rest_upload_image_type_not_supported");
        Response svg = upload(
                "bob",
                "/",
                "x.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                Map.of());
        assertThat(svg.statusCode()).as(svg.asString()).isEqualTo(500);
        assertThat(svg.jsonPath().getString("code")).isEqualTo("upload_error");
        assertThat(svg.jsonPath().getString("message"))
                .isEqualTo("Sorry, you are not allowed to upload this file type.");
        // no token: no upload
        Response anonymous = upload(null, "/", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8), Map.of());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.jsonPath().getString("code")).isEqualTo("unauthorized");
        // a subscriber may not upload
        role("erin", "subscriber");
        Response subscriber = upload("erin", "/", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8), Map.of());
        assertThat(subscriber.statusCode()).as(subscriber.asString()).isEqualTo(403);
        assertThat(subscriber.jsonPath().getString("code")).isEqualTo("forbidden");
        // an attachment as parent: the REST preamble's code
        long attachment = uploaded("bob", "parent.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8))
                .getLong("id");
        Response attached = upload(
                "bob", "/", "child.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8), Map.of("post", "" + attachment));
        assertThat(attached.statusCode()).as(attached.asString()).isEqualTo(400);
        assertThat(attached.jsonPath().getString("code")).isEqualTo("rest_invalid_param");
        assertThat(attached.jsonPath().getString("message")).isEqualTo("Invalid parent type.");
        // a post that is no attachment cannot be updated as one: the posts module's refusal
        Response notMedia = as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("title", "x"))
                .put("/it/media/" + post("Not media"));
        assertThat(notMedia.statusCode()).as(notMedia.asString()).isEqualTo(400);
        assertThat(notMedia.jsonPath().getString("code")).isEqualTo("invalid_post");
        // a post field that is no post id is a bad request, not a server error
        Response malformed =
                upload("bob", "/", "a.jpg", "image/jpeg", MediaFixtures.jpeg(8, 8), Map.of("post", "twelve"));
        assertThat(malformed.statusCode()).isEqualTo(400);
    }

    @Test
    void deleteRemovesTheFilesFromTheRoute() {
        JsonPath media = uploaded("bob", "gone.jpg", "image/jpeg", MediaFixtures.jpeg(400, 300));
        long id = media.getLong("id");
        List<String> urls = new ArrayList<>();
        urls.add(media.getString("url"));
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        assertThat(sizes).isNotEmpty();
        sizes.values().forEach(size -> urls.add((String) size.get("url")));
        for (String url : urls) {
            assertThat(fetch(url).statusCode()).as(url).isEqualTo(200);
        }
        as("bob").delete("/it/media/" + id).then().statusCode(200);
        for (String url : urls) {
            assertThat(fetch(url).statusCode()).as(url).isEqualTo(404);
        }
        as("bob").get("/it/media/" + id).then().statusCode(404);
    }

    /** A draft post on the main site, created by bob (an editor); answers its id. */
    private long post(String title) {
        return as("bob")
                .contentType(ContentType.JSON)
                .body(Map.of("type", "post", "status", "draft", "title", title))
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** The ids bob finds in the main site's library with these query arguments. */
    private List<Long> ids(Map<String, ?> arguments) {
        return as("bob")
                .queryParams(arguments)
                .get("/it/media")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", Long.class);
    }

    /** Form fields in the order given (name, value, name, value, ...). */
    private static Map<String, String> orderedFields(String... namesAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return fields;
    }

    private JsonPath inspect(byte[] bytes) {
        Response response = as(null).contentType(ContentType.BINARY).body(bytes).post("/it/media/inspect");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return response.jsonPath();
    }

    private static String file(String attachedFile) {
        return attachedFile.substring(attachedFile.lastIndexOf('/') + 1);
    }

    private static byte[] clayRgb() throws IOException {
        try (InputStream in = MediaDbTest.class.getResourceAsStream("/profiles/ClayRGB1998.icc")) {
            assertThat(in)
                    .as("TwelveMonkeys' Adobe RGB (1998) compatible profile")
                    .isNotNull();
            return in.readAllBytes();
        }
    }

    /** The ICC profile of a JPEG: its {@code ICC_PROFILE} APP2 segments' data, in order; null when there is none. */
    private static byte[] jpegProfile(byte[] jpeg) {
        byte[] signature = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream profile = new ByteArrayOutputStream();
        int at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            int data = at + 4;
            if (marker == 0xE2 && length >= 2 + signature.length + 2) {
                boolean icc = true;
                for (int i = 0; i < signature.length; i++) {
                    icc &= jpeg[data + i] == signature[i];
                }
                if (icc) {
                    int start = data + signature.length + 2; // sequence number and count
                    profile.write(jpeg, start, at + 2 + length - start);
                }
            }
            at += 2 + length;
        }
        return profile.size() == 0 ? null : profile.toByteArray();
    }

    /** A {@code w} by {@code h} CMYK JPEG: a four-band raster written by the JDK's own encoder (Adobe APP14). */
    private static byte[] cmykJpeg(int w, int h) throws IOException {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, w, h, 4, null);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setPixel(x, y, new int[] {x * 255 / w, y * 255 / h, 40, 20});
            }
        }
        ImageWriter writer = null;
        for (Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg"); writers.hasNext(); ) {
            ImageWriter candidate = writers.next();
            if (candidate.getClass().getName().startsWith("com.sun.")) {
                writer = candidate;
            }
        }
        assertThat(writer).as("the JDK's JPEG writer").isNotNull();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
