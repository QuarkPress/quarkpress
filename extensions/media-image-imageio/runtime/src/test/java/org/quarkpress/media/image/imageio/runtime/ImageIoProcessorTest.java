package org.quarkpress.media.image.imageio.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import java.awt.Color;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/** Reading, decoding, orienting, cutting, scaling and writing images with ImageIO and the TwelveMonkeys plugins. */
class ImageIoProcessorTest {

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0x0000FF;
    private static final int WHITE = 0xFFFFFF;

    private final ImageIoProcessor p = new ImageIoProcessor(100_000_000);

    @TempDir
    Path dir;

    private int files;

    @Test
    void supportsWhatItCanReadAndWrite() {
        for (String t : List.of("image/jpeg", "image/png", "image/gif", "image/bmp")) {
            assertThat(p.supports(t)).as(t).isTrue();
        }
        for (String t : List.of(
                "image/webp",
                "image/avif",
                "image/heic",
                "image/heif",
                "image/tiff",
                "image/x-icon",
                "image/vnd.microsoft.icon",
                "image/svg+xml",
                "image/jpg",
                "image/pjpeg")) {
            assertThat(p.supports(t)).as(t).isFalse();
        }
    }

    @Test
    void readGivesRawDimensionsAndTheRealType() throws Exception {
        assertThat(p.read(write(MediaFixtures.jpeg(64, 48, orientation(6)))))
                .contains(new ImageInfo("image/jpeg", 64, 48));
        assertThat(p.read(write(MediaFixtures.webp()))).contains(new ImageInfo("image/webp", 24, 16));
        assertThat(p.read(write(MediaFixtures.png(5, 4)))).contains(new ImageInfo("image/png", 5, 4));
        assertThat(p.read(write(MediaFixtures.gif(6, 3, 2)))).contains(new ImageInfo("image/gif", 6, 3));
        assertThat(p.read(write(MediaFixtures.bmp(7, 2)))).contains(new ImageInfo("image/bmp", 7, 2));
        assertThat(p.read(write(MediaFixtures.tiff(3, 2)))).contains(new ImageInfo("image/tiff", 3, 2));
        assertThat(p.read(write(MediaFixtures.text("x")))).isEmpty();
        assertThat(p.read(write(MediaFixtures.pngHeaderOnly(40000, 40000))))
                .contains(new ImageInfo("image/png", 40000, 40000));
        assertThat(p.read(dir.resolve("missing.png"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2,3,2", "3,3,2", "4,3,2", "5,2,3", "6,2,3", "7,2,3", "8,2,3"})
    void exifOrientationsAreCorrected(int orientation, int width, int height) throws Exception {
        try (DecodedImage source = p.decode(write(corners()), "image/png")) {
            DecodedImage turned = source.oriented(orientation);
            assertThat(turned.width()).isEqualTo(width);
            assertThat(turned.height()).isEqualTo(height);
            // the standard EXIF correction: the corners top-left, top-right, bottom-left, bottom-right
            int[] expected = switch (orientation) {
                case 2 -> new int[] {GREEN, RED, WHITE, BLUE}; // mirrored
                case 3 -> new int[] {WHITE, BLUE, GREEN, RED}; // rotated 180
                case 4 -> new int[] {BLUE, WHITE, RED, GREEN}; // flipped
                case 5 -> new int[] {RED, BLUE, GREEN, WHITE}; // transposed
                case 6 -> new int[] {BLUE, RED, WHITE, GREEN}; // rotated 90 clockwise
                case 7 -> new int[] {WHITE, GREEN, BLUE, RED}; // transversed
                default -> new int[] {GREEN, WHITE, RED, BLUE}; // rotated 90 counter-clockwise
            };
            assertThat(cornersOf(turned)).containsExactly(expected);
            assertThat(cornersOf(source)).containsExactly(RED, GREEN, BLUE, WHITE);
            turned.close();
            assertThat(source.oriented(1)).isSameAs(source);
            assertThat(source.oriented(0)).isSameAs(source);
            assertThat(source.oriented(9)).isSameAs(source);
        }
    }

    @Test
    void aSubSizeIsBaselineJpegWithTheColourProfileAndNoOtherMetadata() throws Exception {
        JpegMeta meta = JpegMeta.none()
                .withExif("Model", "Camera")
                .withExif("Artist", "Somebody")
                .withIptc(120, "A caption")
                .withXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                        + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>")
                .withIcc(clayRgb());
        Path source = write(MediaFixtures.jpeg(4000, 3000, meta));
        Metadata in = ImageMetadataReader.readMetadata(source.toFile());
        assertThat(in.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNotNull();
        assertThat(in.getFirstDirectoryOfType(IptcDirectory.class)).isNotNull();
        assertThat(in.getFirstDirectoryOfType(XmpDirectory.class)).isNotNull();
        Path target = dir.resolve("out.jpg");
        try (DecodedImage decoded = p.decode(source, "image/jpeg");
                DecodedImage region = decoded.region(500, 0, 3000, 3000, 150, 150)) {
            Encoded out = region.encode(target, "image/jpeg", 82);
            assertThat(out).isEqualTo(new Encoded("image/jpeg", 150, 150, Files.size(target)));
            assertThat(decoded.width()).isEqualTo(4000);
            assertThat(decoded.height()).isEqualTo(3000);
        }
        Metadata m = ImageMetadataReader.readMetadata(target.toFile());
        assertThat(m.getFirstDirectoryOfType(IccDirectory.class)).isNotNull();
        assertThat(m.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        assertThat(m.getFirstDirectoryOfType(IptcDirectory.class)).isNull();
        assertThat(m.getFirstDirectoryOfType(XmpDirectory.class)).isNull();
        assertThat(jpegProfile(target))
                .isEqualTo(ICC_Profile.getInstance(clayRgb()).getData());
        assertThat(hasMarker(target, 0xC0)).isTrue(); // SOF0 baseline
        assertThat(hasMarker(target, 0xC2)).isFalse(); // never progressive
        ImageWriter writer = jpegWriter();
        try {
            assertThat(ImageIoProcessor.jpegParam(writer, 82).getCompressionQuality())
                    .isEqualTo(0.82f);
            assertThat(ImageIoProcessor.jpegParam(writer, 82).getProgressiveMode())
                    .isEqualTo(javax.imageio.ImageWriteParam.MODE_DISABLED);
        } finally {
            writer.dispose();
        }
    }

    @Test
    void theQualityIsTheOneAsked() throws Exception {
        try (DecodedImage decoded = p.decode(write(MediaFixtures.jpeg(400, 300)), "image/jpeg")) {
            long low = decoded.encode(dir.resolve("low.jpg"), "image/jpeg", 20).bytes();
            long high =
                    decoded.encode(dir.resolve("high.jpg"), "image/jpeg", 95).bytes();
            assertThat(low).isLessThan(high);
            // outside 1 to 100 the quality is clamped: the JPEG encoder is deterministic, so the sizes are equal
            long zero = decoded.encode(dir.resolve("q0.jpg"), "image/jpeg", 0).bytes();
            long negative =
                    decoded.encode(dir.resolve("q-5.jpg"), "image/jpeg", -5).bytes(); // unclamped: refused
            long one = decoded.encode(dir.resolve("q1.jpg"), "image/jpeg", 1).bytes();
            long over =
                    decoded.encode(dir.resolve("q150.jpg"), "image/jpeg", 150).bytes();
            long hundred =
                    decoded.encode(dir.resolve("q100.jpg"), "image/jpeg", 100).bytes();
            assertThat(zero).isEqualTo(one);
            assertThat(negative).isEqualTo(one);
            assertThat(over).isEqualTo(hundred);
            assertThat(one).isLessThan(hundred);
        }
    }

    @Test
    void scalingAveragesThePixelsItReplaces() throws Exception {
        // a 1-pixel black and white checkerboard: every averaged output pixel is mid-grey, never black or white
        BufferedImage board = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                board.setRGB(x, y, (x + y) % 2 == 0 ? 0x000000 : WHITE);
            }
        }
        Path target = dir.resolve("board.png");
        try (DecodedImage decoded = p.decode(write(encode(board, "png")), "image/png");
                DecodedImage small = decoded.region(0, 0, 64, 64, 4, 4)) {
            small.encode(target, "image/png", 82);
        }
        Raster out = ImageIO.read(target.toFile()).getRaster();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(out.getSample(x, y, 0)).as("pixel %d,%d", x, y).isBetween(120, 135);
            }
        }
    }

    @Test
    void transparentPixelsLendNoColour() throws Exception {
        // left a fully transparent red pixel, right an opaque blue one: their average is half-transparent blue
        BufferedImage edge = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        edge.setRGB(0, 0, 0x00FF0000);
        edge.setRGB(1, 0, 0xFF0000FF);
        Path target = dir.resolve("edge.png");
        try (DecodedImage decoded = p.decode(write(encode(edge, "png")), "image/png");
                DecodedImage one = decoded.region(0, 0, 2, 1, 1, 1)) {
            one.encode(target, "image/png", 82);
        }
        int pixel = ImageIO.read(target.toFile()).getRGB(0, 0);
        assertThat((pixel >> 16) & 0xFF).as("red").isLessThanOrEqualTo(2);
        assertThat(pixel & 0xFF).as("blue").isGreaterThanOrEqualTo(253);
        assertThat(pixel >>> 24).as("alpha").isBetween(126, 129);
    }

    @Test
    void aGifFrameIsPlacedOnItsLogicalScreen() throws Exception {
        Path gif = write(offsetGif());
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                assertThat(reader.getWidth(0)).as("the fixture's frame").isEqualTo(4);
            } finally {
                reader.dispose();
            }
        }
        Path target = dir.resolve("screen.png");
        try (DecodedImage decoded = p.decode(gif, "image/gif")) {
            assertThat(decoded.width()).isEqualTo(10);
            assertThat(decoded.height()).isEqualTo(10);
            try (DecodedImage all = decoded.region(0, 0, 10, 10, 10, 10)) {
                all.encode(target, "image/png", 82);
            }
        }
        // every pixel of the screen: the frame as the JDK reads it, at (3, 1); fully transparent elsewhere
        BufferedImage frame = ImageIO.read(gif.toFile());
        assertThat(frame.getRGB(0, 0)).as("the fixture's frame").isEqualTo(0xFFFF0000);
        BufferedImage out = ImageIO.read(target.toFile());
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                boolean inside = x >= 3 && x < 7 && y >= 1 && y < 5;
                if (inside) {
                    assertThat(out.getRGB(x, y)).as("%d,%d", x, y).isEqualTo(frame.getRGB(x - 3, y - 1));
                } else {
                    assertThat(out.getRGB(x, y) >>> 24).as("%d,%d", x, y).isZero();
                }
            }
        }
        // the pixel limit applies to the logical screen, not the frame
        assertThatThrownBy(() -> new ImageIoProcessor(99).decode(gif, "image/gif"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("The image is too large to process: 10x10 pixels.");
    }

    @Test
    void cmykJpegsAreDecodedToRgbWithoutTheirProfile() throws Exception {
        Path source = dir.resolve("cmyk.jpg");
        try (InputStream in = getClass().getResourceAsStream("/images/cmyk.jpg")) {
            Files.copy(in, source);
        }
        assertThat(ImageMetadataReader.readMetadata(source.toFile())
                        .getFirstDirectoryOfType(JpegDirectory.class)
                        .getInt(JpegDirectory.TAG_NUMBER_OF_COMPONENTS))
                .isEqualTo(4);
        Path target = dir.resolve("out.jpg");
        try (DecodedImage decoded = p.decode(source, "image/jpeg");
                DecodedImage region = decoded.region(0, 0, decoded.width(), decoded.height(), 16, 12)) {
            assertThat(region.encode(target, "image/jpeg", 82))
                    .isEqualTo(new Encoded("image/jpeg", 16, 12, Files.size(target)));
        }
        Metadata m = ImageMetadataReader.readMetadata(target.toFile());
        assertThat(m.getFirstDirectoryOfType(JpegDirectory.class).getInt(JpegDirectory.TAG_NUMBER_OF_COMPONENTS))
                .isEqualTo(3);
        assertThat(m.getFirstDirectoryOfType(IccDirectory.class)).isNull();
        assertThat(ImageIO.read(target.toFile()).getColorModel().getColorSpace().getType())
                .isEqualTo(ColorSpace.TYPE_RGB);
    }

    @Test
    void aKeptProfileDescribesTheWrittenPixels() throws Exception {
        ICC_Profile linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB);
        Path target = dir.resolve("out.png");
        try (DecodedImage decoded = p.decode(write(linearPng(linear)), "image/png");
                DecodedImage region = decoded.region(0, 0, 2, 2, 1, 1)) {
            assertThat(region.encode(target, "image/png", 82))
                    .isEqualTo(new Encoded("image/png", 1, 1, Files.size(target)));
        }
        assertThat(ImageMetadataReader.readMetadata(target.toFile()).getFirstDirectoryOfType(IccDirectory.class))
                .isNotNull();
        assertThat(pngProfile(target)).isEqualTo(linear.getData());
        // The JDK's PNG reader does not apply the profile: these are the samples as stored.
        Raster written = ImageIO.read(target.toFile()).getRaster();
        for (int band = 0; band < 3; band++) {
            assertThat(written.getSample(0, 0, band)).as("band %d", band).isCloseTo(64, within(1));
        }

        Path greyTarget = dir.resolve("grey.jpg");
        try (DecodedImage decoded = p.decode(write(greyJpeg()), "image/jpeg");
                DecodedImage region = decoded.region(0, 0, 4, 4, 2, 2)) {
            region.encode(greyTarget, "image/jpeg", 82);
        }
        Metadata m = ImageMetadataReader.readMetadata(greyTarget.toFile());
        assertThat(m.getFirstDirectoryOfType(JpegDirectory.class).getInt(JpegDirectory.TAG_NUMBER_OF_COMPONENTS))
                .isEqualTo(1);
        assertThat(jpegProfile(greyTarget))
                .isEqualTo(ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData());
        BufferedImage grey = ImageIO.read(greyTarget.toFile());
        assertThat(grey.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
        assertThat(grey.getRaster().getSample(1, 1, 0)).isCloseTo(100, within(2));
    }

    @Test
    void otherFormatsKeepTheirType() throws Exception {
        Path png = dir.resolve("out.png");
        try (DecodedImage decoded = p.decode(write(alphaPng()), "image/png");
                DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
            assertThat(region.encode(png, "image/png", 82)).isEqualTo(new Encoded("image/png", 4, 3, Files.size(png)));
        }
        BufferedImage pngOut = ImageIO.read(png.toFile());
        assertThat(pngOut.getColorModel().hasAlpha()).isTrue();
        assertThat(pngOut.getRGB(0, 0) >>> 24).isZero(); // the transparent left half stays transparent
        assertThat(pngOut.getRGB(3, 0) >>> 24).isEqualTo(255);
        assertThat(pngOut.getWidth()).isEqualTo(4);
        assertThat(pngOut.getHeight()).isEqualTo(3);

        Path gif = dir.resolve("out.gif");
        try (DecodedImage decoded = p.decode(write(MediaFixtures.gif(8, 6, 3)), "image/gif");
                DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
            assertThat(region.encode(gif, "image/gif", 82)).isEqualTo(new Encoded("image/gif", 4, 3, Files.size(gif)));
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                assertThat(reader.getFormatName()).isEqualToIgnoringCase("gif");
                assertThat(reader.getNumImages(true)).isEqualTo(1);
                assertThat(reader.getWidth(0)).isEqualTo(4);
                assertThat(reader.getHeight(0)).isEqualTo(3);
            } finally {
                reader.dispose();
            }
        }

        Path bmp = dir.resolve("out.bmp");
        try (DecodedImage decoded = p.decode(write(MediaFixtures.bmp(8, 6)), "image/bmp");
                DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
            assertThat(region.encode(bmp, "image/bmp", 82)).isEqualTo(new Encoded("image/bmp", 4, 3, Files.size(bmp)));
        }
        BufferedImage bmpOut = ImageIO.read(bmp.toFile());
        assertThat(bmpOut.getColorModel().getPixelSize()).isEqualTo(24);
        assertThat(bmpOut.getWidth()).isEqualTo(4);
        assertThat(bmpOut.getHeight()).isEqualTo(3);
        assertThat(p.read(bmp)).contains(new ImageInfo("image/bmp", 4, 3));
    }

    @Test
    void aRegionIsCutFromTheRightPlace() throws Exception {
        Path target = dir.resolve("right.png");
        try (DecodedImage decoded = p.decode(write(MediaFixtures.halvesPng(400, 100)), "image/png");
                DecodedImage right = decoded.region(200, 0, 200, 100, 20, 10)) {
            right.encode(target, "image/png", 82);
        }
        BufferedImage out = ImageIO.read(target.toFile());
        assertThat(out.getRGB(0, 0) & 0xFFFFFF).isEqualTo(BLUE);
        assertThat(out.getRGB(19, 9) & 0xFFFFFF).isEqualTo(BLUE);
    }

    @Test
    void anUnsupportedOutputTypeIsRefused() throws Exception {
        try (DecodedImage decoded = p.decode(write(MediaFixtures.png(4, 4)), "image/png")) {
            assertThatThrownBy(() -> decoded.encode(dir.resolve("out.webp"), "image/webp", 82))
                    .isInstanceOf(ImageProcessingException.class);
        }
    }

    @Test
    void imagesAboveThePixelLimitAreNotDecoded() {
        assertThatThrownBy(() -> new ImageIoProcessor(100_000_000)
                        .decode(write(MediaFixtures.pngHeaderOnly(40000, 40000)), "image/png"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("The image is too large to process: 40000x40000 pixels.");
    }

    @Test
    void aSideLongerThanTheLongestRowIsNotDecoded() throws Exception {
        // within the pixel limit, but a row this long would need row buffers of hundreds of megabytes
        for (int[] size : new int[][] {{100_000_000, 1}, {1, 100_000_000}, {65536, 2}}) {
            assertThatThrownBy(() -> new ImageIoProcessor(100_000_000)
                            .decode(write(MediaFixtures.pngHeaderOnly(size[0], size[1])), "image/png"))
                    .isInstanceOf(ImageProcessingException.class)
                    .hasMessage("The image is too large to process: " + size[0] + "x" + size[1]
                            + " pixels (a side may have at most 65535).");
        }
        BufferedImage edge = new BufferedImage(65535, 1, BufferedImage.TYPE_INT_RGB);
        try (DecodedImage decoded = p.decode(write(encode(edge, "png")), "image/png")) {
            assertThat(decoded.width()).isEqualTo(65535);
        }
    }

    @Test
    void aTruncatedImageFailsCleanly() throws Exception {
        byte[] jpeg = MediaFixtures.jpeg(400, 300);
        Path half = write(Arrays.copyOf(jpeg, jpeg.length / 2));
        assertThatThrownBy(() -> p.decode(half, "image/jpeg")).isInstanceOf(ImageProcessingException.class);
        byte[] png = MediaFixtures.png(400, 300);
        Path halfPng = write(Arrays.copyOf(png, png.length / 2));
        assertThatThrownBy(() -> p.decode(halfPng, "image/png")).isInstanceOf(ImageProcessingException.class);
        byte[] gif = MediaFixtures.gif(400, 300, 1);
        Path halfGif = write(Arrays.copyOf(gif, gif.length / 2));
        assertThatThrownBy(() -> p.decode(halfGif, "image/gif")).isInstanceOf(ImageProcessingException.class);
        byte[] bmp = MediaFixtures.bmp(400, 300);
        Path halfBmp = write(Arrays.copyOf(bmp, bmp.length / 2));
        assertThatThrownBy(() -> p.decode(halfBmp, "image/bmp")).isInstanceOf(ImageProcessingException.class);
        assertThatThrownBy(() -> p.decode(write(MediaFixtures.text("x")), "image/png"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("No image reader knows the file's type.");
        // the messages are sentences, with at most the reader's own message after them, never Java class names
        for (Path broken : List.of(half, halfPng, halfGif, halfBmp)) {
            assertThatThrownBy(() -> p.decode(broken, "image/png"))
                    .isInstanceOf(ImageProcessingException.class)
                    .satisfies(e -> assertThat(e.getMessage())
                            .startsWith("The image could not be decoded")
                            .doesNotContain("java.", "javax.", "Exception"));
        }
    }

    @Test
    void compressedChunksCannotExhaustMemory() throws Exception {
        byte[] gigabyte = zeros(1L << 30); // about 1 MB of zlib data that inflates to 1 GiB
        ByteArrayOutputStream iccp = new ByteArrayOutputStream();
        iccp.writeBytes("icc".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        iccp.write(0); // name end
        iccp.write(0); // deflate
        iccp.writeBytes(gigabyte);
        ByteArrayOutputStream ztxt = new ByteArrayOutputStream();
        ztxt.writeBytes("Comment".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        ztxt.write(0);
        ztxt.write(0);
        ztxt.writeBytes(gigabyte);
        for (byte[] chunk : List.of(chunk("iCCP", iccp.toByteArray()), chunk("zTXt", ztxt.toByteArray()))) {
            Path file = write(pngWith(chunk));
            long start = System.nanoTime();
            assertThat(p.read(file)).contains(new ImageInfo("image/png", 4, 4));
            Path target = dir.resolve("bomb-" + files++ + ".png");
            try (DecodedImage decoded = p.decode(file, "image/png")) { // the chunk is ignored
                assertThat(decoded.width()).isEqualTo(4);
                decoded.encode(target, "image/png", 82);
            }
            assertThat(pngProfile(target)).isNull();
            assertThat(System.nanoTime() - start).as("nanoseconds").isLessThan(5_000_000_000L);
        }
        // a tEXt chunk declaring almost 2^31 bytes, of which the file holds a few
        byte[] huge = new byte[16];
        java.nio.ByteBuffer.wrap(huge)
                .putInt(0x7FFFFFF0)
                .put("tEXtComment".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Path text = write(pngWith(huge));
        // the header is read without touching the chunk, or the file is refused; never an Error
        assertThat(p.read(text))
                .isIn(java.util.Optional.empty(), java.util.Optional.of(new ImageInfo("image/png", 4, 4)));
        assertThatThrownBy(() -> p.decode(text, "image/png")).isInstanceOf(ImageProcessingException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private Path write(byte[] bytes) throws IOException {
        return Files.write(dir.resolve("in-" + files++), bytes);
    }

    private static JpegMeta orientation(int value) {
        return JpegMeta.none().withOrientation(value);
    }

    private static byte[] clayRgb() throws IOException {
        try (InputStream in = ImageIoProcessorTest.class.getResourceAsStream("/profiles/ClayRGB1998.icc")) {
            return in.readAllBytes();
        }
    }

    /** A 3x2 PNG whose corners are red (top left), green (top right), blue (bottom left) and white. */
    private static byte[] corners() throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, RED);
        image.setRGB(2, 0, GREEN);
        image.setRGB(0, 1, BLUE);
        image.setRGB(2, 1, WHITE);
        return encode(image, "png");
    }

    /** The corner colours of {@code image}, written as a PNG: top left, top right, bottom left, bottom right. */
    private int[] cornersOf(DecodedImage image) throws Exception {
        Path target = dir.resolve("corners-" + files++ + ".png");
        image.encode(target, "image/png", 82);
        BufferedImage out = ImageIO.read(target.toFile());
        int w = out.getWidth() - 1;
        int h = out.getHeight() - 1;
        return new int[] {
            out.getRGB(0, 0) & 0xFFFFFF,
            out.getRGB(w, 0) & 0xFFFFFF,
            out.getRGB(0, h) & 0xFFFFFF,
            out.getRGB(w, h) & 0xFFFFFF
        };
    }

    /** A 2x2 PNG whose every sample is 64, with an {@code iCCP} chunk holding {@code profile}. */
    private static byte[] linearPng(ICC_Profile profile) throws IOException {
        ComponentColorModel model = new ComponentColorModel(
                new ICC_ColorSpace(profile), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(2, 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                for (int band = 0; band < 3; band++) {
                    raster.setSample(x, y, band, 64);
                }
            }
        }
        BufferedImage image = new BufferedImage(model, raster, false, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            IIOMetadataNode iccp = new IIOMetadataNode("iCCP");
            iccp.setAttribute("profileName", "linear");
            iccp.setAttribute("compressionMethod", "deflate");
            iccp.setUserObject(deflate(profile.getData())); // the JDK writes the bytes as they are: zlib data
            root.appendChild(iccp);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.write(null, new IIOImage(image, null, metadata), null);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A 4x4 grey JPEG of samples 100 tagged with the JDK's grey profile, as the JDK's encoder writes it. */
    private static byte[] greyJpeg() throws IOException {
        ComponentColorModel model = new ComponentColorModel(
                new ICC_ColorSpace(ICC_Profile.getInstance(ColorSpace.CS_GRAY)),
                false,
                false,
                Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                raster.setSample(x, y, 0, 100);
            }
        }
        byte[] bytes = encode(new BufferedImage(model, raster, false, null), "jpeg");
        assertThat(jpegProfileOf(bytes)).isNotNull();
        return bytes;
    }

    /** A GIF of a 10x10 logical screen whose one frame is 4x4 opaque red at (3, 1). */
    private static byte[] offsetGif() throws IOException {
        // a palette of black and red and raw samples 1: no colour conversion decides the frame's colour
        java.awt.image.IndexColorModel palette = new java.awt.image.IndexColorModel(
                8, 2, new byte[] {0, (byte) 0xFF}, new byte[] {0, 0}, new byte[] {0, 0});
        WritableRaster samples = palette.createCompatibleWritableRaster(4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                samples.setSample(x, y, 0, 1);
            }
        }
        BufferedImage frame = new BufferedImage(palette, samples, false, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            IIOMetadata streamMetadata = writer.getDefaultStreamMetadata(null);
            String streamFormat = streamMetadata.getNativeMetadataFormatName();
            IIOMetadataNode streamTree = (IIOMetadataNode) streamMetadata.getAsTree(streamFormat);
            IIOMetadataNode screen = (IIOMetadataNode)
                    streamTree.getElementsByTagName("LogicalScreenDescriptor").item(0);
            screen.setAttribute("logicalScreenWidth", "10");
            screen.setAttribute("logicalScreenHeight", "10");
            streamMetadata.setFromTree(streamFormat, streamTree);
            IIOMetadata imageMetadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(frame), null);
            String imageFormat = imageMetadata.getNativeMetadataFormatName();
            IIOMetadataNode imageTree = (IIOMetadataNode) imageMetadata.getAsTree(imageFormat);
            IIOMetadataNode descriptor = (IIOMetadataNode)
                    imageTree.getElementsByTagName("ImageDescriptor").item(0);
            descriptor.setAttribute("imageLeftPosition", "3");
            descriptor.setAttribute("imageTopPosition", "1");
            imageMetadata.setFromTree(imageFormat, imageTree);
            writer.write(streamMetadata, new IIOImage(frame, null, imageMetadata), null);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** An 8x6 PNG whose left half is transparent and right half opaque red. */
    private static byte[] alphaPng() throws IOException {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 4; x < 8; x++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        return encode(image, "png");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    private static ImageWriter jpegWriter() {
        return ImageIO.getImageWritersByFormatName("jpeg").next();
    }

    /** The bytes of the ICC profile in the JPEG's APP2 segments, or null. */
    private static byte[] jpegProfile(Path file) throws IOException {
        return jpegProfileOf(Files.readAllBytes(file));
    }

    private static byte[] jpegProfileOf(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                IIOMetadata metadata = reader.getImageMetadata(0);
                IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
                var nodes = tree.getElementsByTagName("app2ICC");
                return nodes.getLength() == 0
                        ? null
                        : ((ICC_Profile) ((IIOMetadataNode) nodes.item(0)).getUserObject()).getData();
            } finally {
                reader.dispose();
            }
        }
    }

    /** The bytes of the profile in the PNG's {@code iCCP} chunk, or null. */
    private static byte[] pngProfile(Path file) throws IOException {
        // read from the chunk itself, as any PNG reader must: name, NUL, compression method 0, then zlib data
        byte[] b = Files.readAllBytes(file);
        for (int i = 8; i + 8 <= b.length; ) {
            int length = java.nio.ByteBuffer.wrap(b, i, 4).getInt();
            String type = new String(b, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (type.equals("iCCP")) {
                int at = i + 8;
                while (b[at] != 0) {
                    at++;
                }
                assertThat(b[at + 1]).as("compression method").isZero();
                return inflate(Arrays.copyOfRange(b, at + 2, i + 8 + length));
            }
            i += 12 + length;
        }
        return null;
    }

    private static byte[] inflate(byte[] data) {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    throw new AssertionError("the iCCP data ends early");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new AssertionError("the iCCP data is not zlib data", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflate(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** One PNG chunk: length, type, data, CRC. */
    private static byte[] chunk(String type, byte[] data) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(12 + data.length);
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        b.putInt(data.length).put(name).put(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        return b.putInt((int) crc.getValue()).array();
    }

    /** The zlib data of {@code size} zero bytes, inflating about a thousand times its own size. */
    private static byte[] zeros(long size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9);
        try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(out, deflater)) {
            byte[] block = new byte[1 << 20];
            for (long written = 0; written < size; written += block.length) {
                zip.write(block);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /** A 4x4 PNG with {@code extra} inserted right after its IHDR chunk. */
    private static byte[] pngWith(byte[] extra) {
        byte[] plain = MediaFixtures.png(4, 4);
        int afterIhdr = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        out.writeBytes(extra);
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        return out.toByteArray();
    }

    /** Whether the JPEG holds a segment with {@code marker} before its first scan. */
    private static boolean hasMarker(Path file, int marker) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int i = 2; // after SOI
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new IllegalStateException("not a marker at " + i);
            }
            int m = b[i + 1] & 0xFF;
            if (m == marker) {
                return true;
            }
            if (m == 0xDA) {
                return false;
            }
            i += 2 + (((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF));
        }
        return false;
    }
}
