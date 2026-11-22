package org.quarkpress.it.mediavips;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.AvifBoxes;

/**
 * The media library with the libvips image processor, end to end with real Keycloak tokens: uploads through the test
 * resource ({@code /it/media}), stored by the filesystem backend and served by its route, sizes made by libvips, BMP
 * decoded by ImageIO (the fallback). Everything goes over HTTP, so {@code MediaVipsIT} runs the same tests against the
 * packaged application.
 *
 * <p>A type this libvips cannot write or decode skips its test, or fails it with
 * {@code -Dvips.require-all-formats=true} (CI).
 */
@QuarkusTest
@WithTestResource(MediaFilesResource.class)
class MediaVipsDbTest {

    static final String WEBP_SKIP = "SKIPPED (libvips lacks webpsave): install libvips with libwebp";
    static final String AVIF_SKIP =
            "SKIPPED (libvips lacks heifsave with an AV1 encoder): install libheif-plugin-aomenc";
    static final String GIF_SKIP = "SKIPPED (libvips lacks gifsave): install libvips with cgif";
    static final String HEIC_SKIP = "SKIPPED (libvips lacks an HEVC decoder): install libheif-plugin-libde265";

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    @BeforeEach
    void roles() {
        long bob = as("bob")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", bob, "role", "editor"))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    /** Uploads on the main site as bob and expects it stored: the answered attachment. */
    JsonPath uploaded(String name, String declaredType, byte[] bytes, Map<String, String> fields) {
        RequestSpecification request = as("bob").multiPart("file", name, bytes, declaredType);
        fields.forEach(request::multiPart);
        Response response = request.post("/it/media");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        return response.jsonPath();
    }

    JsonPath uploaded(String name, String declaredType, byte[] bytes) {
        return uploaded(name, declaredType, bytes, Map.of());
    }

    /** The path of a file URL, fetched from the application with the network's host. */
    Response fetch(String url) {
        return as(null).get(URI.create(url).getRawPath());
    }

    /** What the application's libvips writes and reads, and which image processor the media module uses. */
    JsonPath formats() {
        return as(null).get("/it/vips/formats").then().statusCode(200).extract().jsonPath();
    }

    static boolean requireAllFormats() {
        return Boolean.getBoolean("vips.require-all-formats");
    }

    void assumeWrites(String type, String skip) {
        assumeTrue(formats().getList("writable", String.class).contains(type) || requireAllFormats(), skip);
    }

    /** Each size's file name by size name. */
    static Map<String, String> files(JsonPath media) {
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        Map<String, String> files = new LinkedHashMap<>();
        sizes.forEach((name, size) -> files.put(name, (String) size.get("file")));
        return files;
    }

    /** The {@code mimeType} of every size. */
    static List<Object> sizeTypes(JsonPath media) {
        Map<String, Map<String, Object>> sizes = media.getMap("sizes");
        return sizes.values().stream()
                .map(size -> size.get("mimeType"))
                .distinct()
                .toList();
    }

    @Test
    void libvipsIsTheImageProcessor() {
        JsonPath formats = formats();
        assertThat(formats.getString("processor"))
                .isEqualTo("org.quarkpress.media.image.vips.runtime.VipsImageProcessor");
        assertThat(formats.getList("writable", String.class)).contains("image/jpeg", "image/png");
    }

    @Test
    void aRotatedLargeJpegIsScaledUpright() {
        JsonPath media = uploaded(
                "upright.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(4000, 3000, MediaFixtures.JpegMeta.none().withOrientation(6)));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("attachedFile")).endsWith("/upright-scaled.jpg");
        assertThat(media.getString("originalImage")).isEqualTo("upright.jpg");
        assertThat(media.getInt("width")).isEqualTo(1920);
        assertThat(media.getInt("height")).isEqualTo(2560);
        // the media app's table for this image (ImageIO), which the media parity run checks against WordPress
        assertThat(files(media))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "upright-150x150.jpg",
                        "medium", "upright-225x300.jpg",
                        "medium_large", "upright-768x1024.jpg",
                        "large", "upright-768x1024.jpg",
                        "1536x1536", "upright-1152x1536.jpg",
                        "2048x2048", "upright-1536x2048.jpg"));
        assertThat(sizeTypes(media)).containsExactly("image/jpeg");
    }

    @Test
    void aPngKeepsPngSizes() {
        JsonPath media = uploaded("flat.png", "image/png", MediaFixtures.png(800, 600));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(files(media))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "flat-150x150.png",
                        "medium", "flat-300x225.png",
                        "medium_large", "flat-768x576.png"));
        assertThat(sizeTypes(media)).containsExactly("image/png");
        assertThat(fetch(media.getString("sizes.medium.url")).contentType()).isEqualTo("image/png");
    }

    @Test
    void aWebpKeepsWebpSizes() {
        assumeWrites("image/webp", WEBP_SKIP);
        JsonPath media = uploaded("wide.webp", "image/webp", MediaFixtures.webpLossy(3000, 2000));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("attachedFile")).endsWith("/wide-scaled.webp");
        assertThat(media.getInt("width")).isEqualTo(2560);
        assertThat(media.getInt("height")).isEqualTo(1707);
        assertThat(media.getMap("sizes")).hasSize(6);
        assertThat(sizeTypes(media)).containsExactly("image/webp");
        assertThat(files(media).values()).allSatisfy(file -> assertThat(file).endsWith(".webp"));
    }

    @Test
    void anAvifKeepsAvifSizes() {
        assumeWrites("image/avif", AVIF_SKIP);
        JsonPath media = uploaded("sky.avif", "image/avif", MediaFixtures.avif(2600, 1800));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("attachedFile")).endsWith("/sky-scaled.avif");
        assertThat(media.getInt("width")).isEqualTo(2560);
        assertThat(sizeTypes(media)).containsExactly("image/avif");
        assertThat(files(media).values()).allSatisfy(file -> assertThat(file).endsWith(".avif"));
    }

    @Test
    void anAnimatedGifGetsStillGifSizes() throws IOException {
        assumeWrites("image/gif", GIF_SKIP);
        JsonPath media = uploaded("moving.gif", "image/gif", MediaFixtures.gif(800, 600, 3));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(files(media))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "moving-150x150.gif",
                        "medium", "moving-300x225.gif",
                        "medium_large", "moving-768x576.gif"));
        for (String size : List.of("thumbnail", "medium", "medium_large")) {
            assertThat(frames(fetch(media.getString("sizes." + size + ".url")).asByteArray()))
                    .as(size)
                    .isEqualTo(1);
        }
    }

    @Test
    void aBmpKeepsItsFileAndGetsJpegSizes() {
        // decoded by ImageIO, the fallback; libvips writes no BMP, so the sizes are JPEG, as WordPress with Imagick
        JsonPath media = uploaded("paint.bmp", "image/bmp", MediaFixtures.bmp(800, 600));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getString("mimeType")).isEqualTo("image/bmp");
        assertThat(media.getString("attachedFile")).endsWith("/paint.bmp");
        assertThat(media.getString("originalImage")).isNull();
        assertThat(files(media))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "paint-150x150.jpg",
                        "medium", "paint-300x225.jpg",
                        "medium_large", "paint-768x576.jpg"));
        assertThat(sizeTypes(media)).containsExactly("image/jpeg");
        assertThat(fetch(media.getString("sizes.medium.url")).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void aPixelBombIsStoredWithoutSizes() {
        JsonPath media = uploaded("bomb.png", "image/png", MediaFixtures.pngHeaderOnly(40000, 40000));
        assertThat(media.getString("processing")).isEqualTo("done");
        assertThat(media.getInt("width")).isEqualTo(40000);
        assertThat(media.getInt("height")).isEqualTo(40000);
        assertThat(media.getMap("sizes")).isEmpty();
        assertThat(media.getString("attachedFile")).endsWith("/bomb.png");
    }

    @Test
    void aJpegWiderThanLibjpegReadsIsStoredWithoutSizes() {
        // the widest a JPEG header can declare (its SOF holds 16 bits, so 70 000 cannot be written) is above libjpeg's
        // 65 500 and well within the pixel limit: libjpeg refuses it at decode, and no libvips text is answered
        JsonPath media = uploaded("wide.jpg", "image/jpeg", jpegDeclaring(MediaFixtures.jpeg(64, 10), 65535, 10));
        assertThat(media.getString("processing")).isEqualTo("partial");
        assertThat(media.getInt("width")).isEqualTo(65535);
        assertThat(media.getInt("height")).isEqualTo(10);
        assertThat(media.getMap("sizes")).isEmpty();
        assertThat(media.getString("originalImage")).isNull();
        assertThat(media.getString("attachedFile")).endsWith("/wide.jpg");
        assertThat(media.prettify()).doesNotContainIgnoringCase("libvips").doesNotContain("65500", "dimension");
    }

    @Test
    void aHalfJpegGetsItsSizesAndAHalfPngNone() {
        // as WordPress with either editor: a JPEG cut in half still gets its sizes
        JsonPath jpeg = uploaded("half.jpg", "image/jpeg", half(MediaFixtures.jpeg(1200, 900)));
        assertThat(jpeg.getString("processing")).isEqualTo("done");
        assertThat(jpeg.getInt("width")).isEqualTo(1200);
        assertThat(jpeg.getInt("height")).isEqualTo(900);
        assertThat(files(jpeg))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "thumbnail", "half-150x150.jpg",
                        "medium", "half-300x225.jpg",
                        "medium_large", "half-768x576.jpg",
                        "large", "half-1024x768.jpg"));
        // a PNG cut in half is not decoded: stored with its header's dimensions, no sizes
        JsonPath png = uploaded("half.png", "image/png", half(MediaFixtures.png(1200, 900)));
        assertThat(png.getString("processing")).isEqualTo("partial");
        assertThat(png.getInt("width")).isEqualTo(1200);
        assertThat(png.getInt("height")).isEqualTo(900);
        assertThat(png.getMap("sizes")).isEmpty();
        assertThat(png.getString("originalImage")).isNull();
    }

    @Test
    void aDecodeOnTheEventLoopFailsLoudly() {
        JsonPath answer = as(null).contentType(ContentType.BINARY)
                .body(MediaFixtures.jpeg(64, 48))
                .post("/it/vips/event-loop")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(answer.getString("thrown")).isEqualTo("io.quarkus.runtime.BlockingOperationNotAllowedException");
    }

    @Test
    void noImageStaysOpenAfterUploads() {
        // WebP only where libvips writes it (a host without webpsave refuses it), a JPEG in its place otherwise
        boolean webp = formats().getList("writable", String.class).contains("image/webp") || requireAllFormats();
        for (int i = 0; i < 18; i++) {
            int w = 300 + 20 * i;
            switch (i % 3) {
                case 0 -> uploaded("many-" + i + ".jpg", "image/jpeg", MediaFixtures.jpeg(w, 200));
                case 1 -> uploaded("many-" + i + ".png", "image/png", MediaFixtures.png(w, 200));
                default -> {
                    if (webp) {
                        uploaded("many-" + i + ".webp", "image/webp", MediaFixtures.webpLossy(3000, 2000));
                    } else {
                        uploaded("many-" + i + ".jpg", "image/jpeg", MediaFixtures.jpeg(3000, 2000));
                    }
                }
            }
        }
        // two that fail at decode
        assertThat(uploaded("broken.png", "image/png", half(MediaFixtures.png(600, 400)))
                        .getString("processing"))
                .isEqualTo("partial");
        assertThat(uploaded("broken.gif", "image/gif", half(MediaFixtures.gif(600, 400, 1)))
                        .getString("processing"))
                .isEqualTo("partial");
        JsonPath images = as(null).get("/it/vips/open-images")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(images.getLong("open")).isZero();
        assertThat(images.getLong("leaked")).isZero();
    }

    @Test
    void aHeicWhoseIspeLiesGetsNoSizes() {
        assumeTrue(
                formats().getList("readable", String.class).contains("image/heic") || requireAllFormats(), HEIC_SKIP);
        // above max-image-pixels (100 000 000): measured from the declared size, never decoded, stored without sizes
        // (D14)
        JsonPath huge = timed(() -> uploaded("ispe-huge.heic", "image/heic", heicDeclaring(12000, 12000)));
        assertThat(huge.getString("processing")).isEqualTo("done");
        assertThat(huge.getInt("width")).isEqualTo(12000);
        assertThat(huge.getInt("height")).isEqualTo(12000);
        assertThat(huge.getMap("sizes")).isEmpty();
        assertThat(huge.getString("originalImage")).isNull();
        assertThat(huge.getString("attachedFile")).endsWith("/ispe-huge.heic");
        // within the limit but larger than the coded 4032x3024 frame: the decode fails, and the attachment is kept
        // with the declared size and without sizes, as any image that fails to decode
        JsonPath big = timed(() -> uploaded("ispe-big.heic", "image/heic", heicDeclaring(8000, 6000)));
        assertThat(big.getString("processing")).isEqualTo("partial");
        assertThat(big.getInt("width")).isEqualTo(8000);
        assertThat(big.getInt("height")).isEqualTo(6000);
        assertThat(big.getMap("sizes")).isEmpty();
        assertThat(big.getString("originalImage")).isNull();
        assertThat(big.getString("attachedFile")).endsWith("/ispe-big.heic");
        // above libvips's own max-input-pixels (268 402 689): libvips does not measure it, so it keeps its file size
        // only, as a HEIC no processor reads
        JsonPath giant = timed(() -> uploaded("ispe-giant.heic", "image/heic", heicDeclaring(30000, 30000)));
        assertThat(giant.getString("processing")).isEqualTo("done");
        assertThat(giant.getString("width")).isNull();
        assertThat(giant.getString("height")).isNull();
        assertThat(giant.getMap("sizes")).isEmpty();
        assertThat(giant.getString("originalImage")).isNull();
        assertThat(giant.getString("attachedFile")).endsWith("/ispe-giant.heic");
        assertNoImageOpen();
    }

    @Test
    void anAvifGridOfManyTilesIsKeptWithoutSizes() {
        assumeWrites("image/avif", AVIF_SKIP);
        // a grid naming 60 000 tiles (beyond libheif's limit on items) and one naming 250 (within it), with no coded
        // data: each fails to decode, quickly, and is kept with its declared size and no sizes
        for (int tiles : new int[] {60_000, 250}) {
            String name = "tiles-" + tiles + ".avif";
            JsonPath media = timed(() -> uploaded(name, "image/avif", avifGrid(tiles, 4096, 4096)));
            assertThat(media.getString("processing")).as(name).isEqualTo("partial");
            assertThat(media.getInt("width")).as(name).isEqualTo(4096);
            assertThat(media.getInt("height")).as(name).isEqualTo(4096);
            assertThat(media.getMap("sizes")).as(name).isEmpty();
            assertThat(media.getString("originalImage")).as(name).isNull();
            assertThat(media.getString("attachedFile")).as(name).endsWith("/" + name);
        }
        assertNoImageOpen();
    }

    /** What {@code upload} answers, failing when it takes more than 30 seconds. */
    static <T> T timed(Supplier<T> upload) {
        long start = System.nanoTime();
        T answer = upload.get();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
        return answer;
    }

    void assertNoImageOpen() {
        JsonPath images = as(null).get("/it/vips/open-images")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(images.getLong("open")).isZero();
        assertThat(images.getLong("leaked")).isZero();
    }

    /**
     * The committed 4032&times;3024 HEIC with its image spatial extents ({@code ispe}, the size it declares) changed to
     * {@code width}&times;{@code height}; its coded frame stays 4032&times;3024.
     */
    static byte[] heicDeclaring(int width, int height) {
        byte[] bytes = MediaFixtures.heic(4032, 3024);
        byte[] type = "ispe".getBytes(StandardCharsets.US_ASCII);
        int changed = 0;
        for (int at = 4; at + 16 <= bytes.length; at++) {
            if (Arrays.equals(bytes, at, at + 4, type, 0, 4)) {
                // the box type, then version and flags, then the width and height
                ByteBuffer.wrap(bytes, at + 8, 8).putInt(width).putInt(height);
                changed++;
            }
        }
        if (changed != 1) {
            throw new IllegalStateException("the HEIC fixture holds " + changed + " ispe boxes, not one");
        }
        return bytes;
    }

    /**
     * An AVIF whose primary item is a grid of {@code tiles} AV1 tiles ({@code dimg} references), declared {@code
     * width}&times;{@code height}, without any coded data (no item locations, an empty {@code mdat}).
     */
    static byte[] avifGrid(int tiles, int width, int height) {
        byte[][] items = new byte[tiles + 1][];
        int[] ids = new int[tiles];
        items[0] = AvifBoxes.item(1, "grid");
        for (int i = 0; i < tiles; i++) {
            ids[i] = i + 2;
            items[i + 1] = AvifBoxes.item(i + 2, "av01");
        }
        return AvifBoxes.file(
                AvifBoxes.ftyp("avif", "avif", "mif1", "miaf"),
                AvifBoxes.meta(
                        0,
                        AvifBoxes.pitm(0, 1),
                        AvifBoxes.iprp(
                                AvifBoxes.ipco(
                                        AvifBoxes.ispe(width, height), AvifBoxes.pixi(8, 8, 8), AvifBoxes.av1C(0)),
                                AvifBoxes.ipma(0, 0, new int[] {1, 1, 2})),
                        AvifBoxes.iinf(items),
                        AvifBoxes.iref(0, AvifBoxes.dimg(1, ids))),
                AvifBoxes.box("mdat", new byte[4096]));
    }

    /** {@code jpeg} with the width and height of its baseline frame header (SOF0) changed. */
    static byte[] jpegDeclaring(byte[] jpeg, int width, int height) {
        byte[] bytes = jpeg.clone();
        for (int i = 2; i + 9 < bytes.length; ) {
            if ((bytes[i] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("not a JPEG segment at " + i);
            }
            int marker = bytes[i + 1] & 0xFF;
            if (marker == 0xC0) {
                bytes[i + 5] = (byte) (height >> 8);
                bytes[i + 6] = (byte) height;
                bytes[i + 7] = (byte) (width >> 8);
                bytes[i + 8] = (byte) width;
                return bytes;
            }
            i += 2 + (((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF));
        }
        throw new IllegalArgumentException("no baseline frame header");
    }

    /** The first half of {@code bytes}: the header intact, the pixels cut off. */
    static byte[] half(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length / 2);
    }

    /** The frames of a GIF, as the JDK's reader counts them. */
    static int frames(byte[] gif) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }
}
