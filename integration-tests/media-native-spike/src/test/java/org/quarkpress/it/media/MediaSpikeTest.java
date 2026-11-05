package org.quarkpress.it.media;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The spike's pass/fail criteria. {@link MediaSpikeIT} runs the same tests against the native executable. */
@QuarkusTest
class MediaSpikeTest {

    private static final String TWELVEMONKEYS = "com.twelvemonkeys.imageio.plugins.";
    private static final String JDK = "com.sun.imageio.plugins.";

    @Test
    void imageIoFindsTheTwelveMonkeysPlugins() {
        List<String> readers = given().when()
                .get("/media/readers")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList(".", String.class);
        assertThat(readers)
                .contains(
                        TWELVEMONKEYS + "jpeg.JPEGImageReaderSpi",
                        TWELVEMONKEYS + "webp.WebPImageReaderSpi",
                        JDK + "png.PNGImageReaderSpi",
                        JDK + "gif.GIFImageReaderSpi");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // file,       Tika MIME type, reader class,                          w,  h, JPEG components, top-left pixel
        "photo.jpg,  image/jpeg, " + TWELVEMONKEYS + "jpeg.JPEGImageReader, 64, 48, 3,  ",
        "cmyk.jpg,   image/jpeg, " + TWELVEMONKEYS + "jpeg.JPEGImageReader, 32, 24, 4, ",
        "image.png,  image/png,  " + JDK + "png.PNGImageReader,             60, 40, ,  ",
        "image.gif,  image/gif,  " + JDK + "gif.GIFImageReader,             50, 30, ,  ",
        "image.webp, image/webp, " + TWELVEMONKEYS + "webp.WebPImageReader, 24, 16, ,  ff0000",
    })
    void readsAndDetects(
            String file,
            String mimeType,
            String readerClass,
            int width,
            int height,
            Integer jpegComponents,
            String topLeftRgb)
            throws IOException {
        InspectResult result = inspect(image(file));
        assertThat(result.mimeType()).as("Tika").isEqualTo(mimeType);
        assertThat(result.readerClass()).as("reader").isEqualTo(readerClass);
        assertThat(result.width()).isEqualTo(width);
        assertThat(result.height()).isEqualTo(height);
        assertThat(result.jpegComponents()).as("JPEG components").isEqualTo(jpegComponents);
        if (topLeftRgb != null) {
            assertThat(result.topLeftRgb()).isEqualTo(topLeftRgb);
        }
    }

    @Test
    void metadataExtractorReadsExifOrientationAndIptcCaption() throws IOException {
        InspectResult result = inspect(image("photo.jpg"));
        assertThat(result.exifOrientation()).isEqualTo(6);
        assertThat(result.iptcCaption()).isEqualTo("QuarkPress spike caption");
    }

    static Stream<Arguments> resizes() {
        return Stream.of("photo.jpg", "cmyk.jpg", "image.png", "image.gif", "image.webp")
                .flatMap(file -> Stream.of("jpeg", "png", "gif").map(format -> Arguments.of(file, format)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("resizes")
    void resizesAndWrites(String file, String format) throws IOException {
        Response response = given().contentType(ContentType.BINARY)
                .body(image(file))
                .queryParam("format", format)
                .queryParam("width", 20)
                .queryParam("height", 10)
                .when()
                .post("/media/resize");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        byte[] written = response.asByteArray();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(written));
        assertThat(decoded).as("readable by the test JVM").isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(20);
        assertThat(decoded.getHeight()).isEqualTo(10);
        // The same server detects what it wrote.
        assertThat(inspect(written).mimeType()).isEqualTo("image/" + format);
    }

    private static InspectResult inspect(byte[] bytes) {
        Response response =
                given().contentType(ContentType.BINARY).body(bytes).when().post("/media/inspect");
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return response.as(InspectResult.class);
    }

    private static byte[] image(String file) throws IOException {
        try (InputStream in = MediaSpikeTest.class.getResourceAsStream("/images/" + file)) {
            assertThat(in).as(file).isNotNull();
            return in.readAllBytes();
        }
    }
}
