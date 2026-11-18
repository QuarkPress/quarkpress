package org.quarkpress.testing.mediaimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.quarkpress.testing.mediaimage.ContractImages.BLUE;
import static org.quarkpress.testing.mediaimage.ContractImages.GREEN;
import static org.quarkpress.testing.mediaimage.ContractImages.RED;
import static org.quarkpress.testing.mediaimage.ContractImages.WHITE;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * What every image processor owes the media image SPI, whatever library does the work: read headers, decode, orient,
 * cut and scale to exact sizes, write the asked type at the asked quality with the colour profile and no other
 * metadata, and refuse what it cannot do with an {@link ImageProcessingException}. A processor's test class extends this
 * one and adds what only that processor does.
 *
 * <p>The working directory {@link #dir} is made before and deleted after each test by this class rather than by
 * JUnit's {@code @TempDir}, so the contract also runs in Quarkus extension tests, which call the test methods on
 * another instance than the one JUnit fills in.
 */
public abstract class ImageProcessorContract {

    /** A directory for the test's files, new for each test. */
    protected Path dir;

    private int files;

    /** The processor under test, refusing to decode images of more than {@code maxPixels} pixels. */
    protected abstract ImageProcessor processor(long maxPixels);

    /** The types the processor writes, each of which this contract encodes to. */
    protected abstract Set<String> writableTypes();

    /**
     * Whether a kept profile is written with the samples as the source stored them, for a linear-RGB PNG and a grey
     * JPEG. A processor answering false says why in its own tests.
     */
    protected boolean keepsProfileOfLinearRgb() {
        return true;
    }

    @BeforeEach
    public final void makeTheWorkingDirectory() throws IOException {
        dir = Files.createTempDirectory("quarkpress-image-contract-");
        files = 0;
    }

    @AfterEach
    public final void deleteTheWorkingDirectory() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        dir = null;
    }

    @Test
    public final void readGivesRawDimensionsAndTheRealType() throws Exception {
        ImageProcessor p = processor(100_000_000);
        assertThat(p.read(write(MediaFixtures.jpeg(64, 48, orientation(6)))))
                .contains(new ImageInfo("image/jpeg", 64, 48));
        assertThat(p.read(write(MediaFixtures.webp()))).contains(new ImageInfo("image/webp", 24, 16));
        assertThat(p.read(write(MediaFixtures.png(5, 4)))).contains(new ImageInfo("image/png", 5, 4));
        assertThat(p.read(write(MediaFixtures.gif(6, 3, 2)))).contains(new ImageInfo("image/gif", 6, 3));
        assertThat(p.read(write(MediaFixtures.tiff(3, 2)))).contains(new ImageInfo("image/tiff", 3, 2));
        assertThat(p.read(write(MediaFixtures.text("x")))).isEmpty();
        assertThat(p.read(write(MediaFixtures.pngHeaderOnly(40000, 40000))))
                .contains(new ImageInfo("image/png", 40000, 40000));
        assertThat(p.read(dir.resolve("missing.png"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2,3,2", "3,3,2", "4,3,2", "5,2,3", "6,2,3", "7,2,3", "8,2,3"})
    public final void exifOrientationsAreCorrected(int orientation, int width, int height) throws Exception {
        ImageProcessor p = processor(100_000_000);
        try (DecodedImage source = p.decode(write(ContractImages.corners()), "image/png")) {
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
    public final void aRegionIsCutFromTheRightPlace() throws Exception {
        ImageProcessor p = processor(100_000_000);
        Path target = dir.resolve("right.png");
        try (DecodedImage decoded = p.decode(write(MediaFixtures.halvesPng(400, 100)), "image/png");
                DecodedImage right = decoded.region(200, 0, 200, 100, 20, 10)) {
            right.encode(target, "image/png", 82);
        }
        BufferedImage out = ImageIO.read(target.toFile());
        assertThat(out.getRGB(0, 0) & 0xFFFFFF).isEqualTo(BLUE);
        assertThat(out.getRGB(19, 9) & 0xFFFFFF).isEqualTo(BLUE);
    }

    /** A source of {@code w}&times;{@code h}, the region (x, y, cw, ch) of it, scaled to tw&times;th. */
    record Cut(int w, int h, int x, int y, int cw, int ch, int tw, int th) {}

    @Test
    public final void regionsHaveExactlyTheTargetSize() {
        Gen<Cut> cuts = random -> {
            int w = Gens.ints(1, 4000).next(random);
            int h = Gens.ints(1, 4000).next(random);
            int cw = Gens.ints(1, w).next(random);
            int ch = Gens.ints(1, h).next(random);
            int x = Gens.ints(0, w - cw).next(random);
            int y = Gens.ints(0, h - ch).next(random);
            int tw = Gens.ints(1, cw).next(random);
            int th = Gens.ints(1, ch).next(random);
            return new Cut(w, h, x, y, cw, ch, tw, th);
        };
        ImageProcessor p = processor(100_000_000);
        Properties.check("regionsHaveExactlyTheTargetSize", 200, cuts, cut -> {
            try {
                Path source = write(ContractImages.plainPng(cut.w(), cut.h()));
                Path target = dir.resolve("cut-" + files++ + ".jpg");
                try (DecodedImage decoded = p.decode(source, "image/png");
                        DecodedImage region =
                                decoded.region(cut.x(), cut.y(), cut.cw(), cut.ch(), cut.tw(), cut.th())) {
                    assertThat(region.width()).as("width").isEqualTo(cut.tw());
                    assertThat(region.height()).as("height").isEqualTo(cut.th());
                    assertThat(region.encode(target, "image/jpeg", 82))
                            .isEqualTo(new Encoded("image/jpeg", cut.tw(), cut.th(), Files.size(target)));
                }
                assertThat(dimensionsOf(target)).containsExactly(cut.tw(), cut.th());
                Files.delete(source);
                Files.delete(target);
            } catch (IOException | ImageProcessingException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    public final void aSubSizeKeepsTheColourProfileAndNoOtherMetadata() throws Exception {
        ImageProcessor p = processor(100_000_000);
        JpegMeta meta = JpegMeta.none()
                .withExif("Model", "Camera")
                .withExif("Artist", "Somebody")
                .withIptc(120, "A caption")
                .withXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                        + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>")
                .withIcc(ContractImages.rgbProfile());
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
        assertThat(ContractImages.jpegProfile(target))
                .isEqualTo(ICC_Profile.getInstance(ContractImages.rgbProfile()).getData());
        assertThat(ContractImages.hasMarker(target, 0xC0)).isTrue(); // SOF0 baseline
        assertThat(ContractImages.hasMarker(target, 0xC2)).isFalse(); // never progressive
    }

    @Test
    public final void theQualityIsTheOneAsked() throws Exception {
        ImageProcessor p = processor(100_000_000);
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
    public final void aKeptProfileDescribesTheWrittenPixels() throws Exception {
        assumeTrue(keepsProfileOfLinearRgb(), "the processor does not keep a linear-RGB or grey profile as stored");
        ImageProcessor p = processor(100_000_000);
        ICC_Profile linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB);
        Path target = dir.resolve("out.png");
        try (DecodedImage decoded = p.decode(write(ContractImages.linearPng(linear)), "image/png");
                DecodedImage region = decoded.region(0, 0, 2, 2, 1, 1)) {
            assertThat(region.encode(target, "image/png", 82))
                    .isEqualTo(new Encoded("image/png", 1, 1, Files.size(target)));
        }
        assertThat(ImageMetadataReader.readMetadata(target.toFile()).getFirstDirectoryOfType(IccDirectory.class))
                .isNotNull();
        assertThat(ContractImages.pngProfile(target)).isEqualTo(linear.getData());
        // The JDK's PNG reader does not apply the profile: these are the samples as stored.
        Raster written = ImageIO.read(target.toFile()).getRaster();
        for (int band = 0; band < 3; band++) {
            assertThat(written.getSample(0, 0, band)).as("band %d", band).isCloseTo(64, within(1));
        }

        Path greyTarget = dir.resolve("grey.jpg");
        try (DecodedImage decoded = p.decode(write(ContractImages.greyJpeg()), "image/jpeg");
                DecodedImage region = decoded.region(0, 0, 4, 4, 2, 2)) {
            region.encode(greyTarget, "image/jpeg", 82);
        }
        Metadata m = ImageMetadataReader.readMetadata(greyTarget.toFile());
        assertThat(m.getFirstDirectoryOfType(JpegDirectory.class).getInt(JpegDirectory.TAG_NUMBER_OF_COMPONENTS))
                .isEqualTo(1);
        assertThat(ContractImages.jpegProfile(greyTarget))
                .isEqualTo(ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData());
        BufferedImage grey = ImageIO.read(greyTarget.toFile());
        assertThat(grey.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
        assertThat(grey.getRaster().getSample(1, 1, 0)).isCloseTo(100, within(2));
    }

    @Test
    public final void cmykJpegsAreDecodedToThreeComponents() throws Exception {
        ImageProcessor p = processor(100_000_000);
        Path source = write(ContractImages.cmykJpeg());
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
        // a kept profile, if any, describes the RGB pixels written, never the CMYK source
        byte[] profile = ContractImages.jpegProfile(target);
        if (profile != null) {
            assertThat(ICC_Profile.getInstance(profile).getColorSpaceType()).isEqualTo(ColorSpace.TYPE_RGB);
        }
    }

    @Test
    public final void otherFormatsKeepTheirType() throws Exception {
        ImageProcessor p = processor(100_000_000);
        Set<String> writable = writableTypes();
        assertThat(writable).contains("image/jpeg", "image/png");
        Path png = dir.resolve("out.png");
        try (DecodedImage decoded = p.decode(write(ContractImages.alphaPng()), "image/png");
                DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
            assertThat(region.encode(png, "image/png", 82)).isEqualTo(new Encoded("image/png", 4, 3, Files.size(png)));
        }
        BufferedImage pngOut = ImageIO.read(png.toFile());
        assertThat(pngOut.getColorModel().hasAlpha()).isTrue();
        assertThat(pngOut.getRGB(0, 0) >>> 24).isZero(); // the transparent left half stays transparent
        assertThat(pngOut.getRGB(3, 0) >>> 24).isEqualTo(255);
        assertThat(pngOut.getWidth()).isEqualTo(4);
        assertThat(pngOut.getHeight()).isEqualTo(3);

        if (writable.contains("image/gif")) {
            Path gif = dir.resolve("out.gif");
            try (DecodedImage decoded = p.decode(write(MediaFixtures.gif(8, 6, 3)), "image/gif");
                    DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
                assertThat(region.encode(gif, "image/gif", 82))
                        .isEqualTo(new Encoded("image/gif", 4, 3, Files.size(gif)));
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
        }

        if (writable.contains("image/bmp")) {
            Path bmp = dir.resolve("out.bmp");
            try (DecodedImage decoded = p.decode(write(MediaFixtures.bmp(8, 6)), "image/bmp");
                    DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
                assertThat(region.encode(bmp, "image/bmp", 82))
                        .isEqualTo(new Encoded("image/bmp", 4, 3, Files.size(bmp)));
            }
            BufferedImage bmpOut = ImageIO.read(bmp.toFile());
            assertThat(bmpOut.getColorModel().getPixelSize()).isEqualTo(24);
            assertThat(bmpOut.getWidth()).isEqualTo(4);
            assertThat(bmpOut.getHeight()).isEqualTo(3);
            assertThat(p.read(bmp)).contains(new ImageInfo("image/bmp", 4, 3));
        }

        // every type written keeps that type and the asked size, as the processor itself reads it back
        for (String type : writable) {
            assertThat(p.supports(type)).as(type).isTrue();
            // as the core hands it over: an existing temporary file without the type's extension, whose old bytes
            // are replaced
            Path out = Files.createTempFile(dir, "quarkpress-media-", ".tmp");
            Files.write(out, new byte[100_000]);
            try (DecodedImage decoded = p.decode(write(MediaFixtures.png(8, 6)), "image/png");
                    DecodedImage region = decoded.region(0, 0, 8, 6, 4, 3)) {
                assertThat(region.encode(out, type, 82)).as(type).isEqualTo(new Encoded(type, 4, 3, Files.size(out)));
            }
            assertThat(p.read(out)).as(type).contains(new ImageInfo(type, 4, 3));
        }
    }

    @Test
    public final void anUnsupportedOutputTypeIsRefused() throws Exception {
        ImageProcessor p = processor(100_000_000);
        assertThat(p.supports("image/heic")).isFalse();
        try (DecodedImage decoded = p.decode(write(MediaFixtures.png(4, 4)), "image/png")) {
            assertThatThrownBy(() -> decoded.encode(dir.resolve("out.heic"), "image/heic", 82))
                    .isInstanceOf(ImageProcessingException.class);
        }
    }

    @Test
    public final void imagesAboveThePixelLimitAreNotDecoded() {
        assertThatThrownBy(() ->
                        processor(100_000_000).decode(write(MediaFixtures.pngHeaderOnly(40000, 40000)), "image/png"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("The image is too large to process: 40000x40000 pixels.");
    }

    @Test
    public final void aTruncatedImageFailsCleanly() throws Exception {
        // the failure may come with the decode or, for a processor reading pixels on demand, with an encode
        ImageProcessor p = processor(100_000_000);
        byte[] jpeg = MediaFixtures.jpeg(400, 300);
        Path half = write(Arrays.copyOf(jpeg, jpeg.length / 2));
        assertThatThrownBy(() -> decodeAndEncode(p, half, "image/jpeg")).isInstanceOf(ImageProcessingException.class);
        byte[] png = MediaFixtures.png(400, 300);
        Path halfPng = write(Arrays.copyOf(png, png.length / 2));
        assertThatThrownBy(() -> decodeAndEncode(p, halfPng, "image/png")).isInstanceOf(ImageProcessingException.class);
        byte[] gif = MediaFixtures.gif(400, 300, 1);
        Path halfGif = write(Arrays.copyOf(gif, gif.length / 2));
        assertThatThrownBy(() -> decodeAndEncode(p, halfGif, "image/gif")).isInstanceOf(ImageProcessingException.class);
        assertThatThrownBy(() -> p.decode(write(MediaFixtures.text("x")), "image/png"))
                .isInstanceOf(ImageProcessingException.class);
    }

    @Test
    public final void operationsLeaveTheirReceiverUnchanged() throws Exception {
        ImageProcessor p = processor(100_000_000);
        try (DecodedImage source = p.decode(write(ContractImages.corners()), "image/png")) {
            try (DecodedImage turned = source.oriented(6)) {
                assertThat(turned.width()).isEqualTo(2);
                assertThat(turned.height()).isEqualTo(3);
                try (DecodedImage cut = turned.region(0, 1, 2, 2, 1, 1)) {
                    assertThat(cut.width()).isEqualTo(1);
                    assertThat(cut.height()).isEqualTo(1);
                }
                assertThat(turned.width()).isEqualTo(2);
                assertThat(turned.height()).isEqualTo(3);
                assertThat(cornersOf(turned)).containsExactly(BLUE, RED, WHITE, GREEN);
            }
            try (DecodedImage cut = source.region(1, 0, 2, 2, 1, 1)) {
                assertThat(cut.width()).isEqualTo(1);
                assertThat(cut.height()).isEqualTo(1);
            }
            // the whole image at its own size is still a new image: closing it leaves the source open
            try (DecodedImage whole = source.region(0, 0, 3, 2, 3, 2)) {
                assertThat(whole.width()).isEqualTo(3);
                assertThat(whole.height()).isEqualTo(2);
            }
            assertThat(source.width()).isEqualTo(3);
            assertThat(source.height()).isEqualTo(2);
            Path target = dir.resolve("source.png");
            assertThat(source.encode(target, "image/png", 82))
                    .isEqualTo(new Encoded("image/png", 3, 2, Files.size(target)));
            assertThat(cornersOf(source)).containsExactly(RED, GREEN, BLUE, WHITE);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Writes {@code bytes} to a new file in {@link #dir}. */
    protected Path write(byte[] bytes) throws IOException {
        return Files.write(dir.resolve("in-" + files++), bytes);
    }

    /** The corner colours of {@code image}, written as a PNG: top left, top right, bottom left, bottom right. */
    protected int[] cornersOf(DecodedImage image) throws Exception {
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

    /** Decodes {@code file}, then encodes it as the core does (a scaled region) and whole. */
    private void decodeAndEncode(ImageProcessor p, Path file, String type) throws ImageProcessingException {
        try (DecodedImage decoded = p.decode(file, type)) {
            int w = decoded.width();
            int h = decoded.height();
            try (DecodedImage half = decoded.region(0, 0, w, h, Math.max(1, w / 2), Math.max(1, h / 2))) {
                half.encode(dir.resolve("half-" + files++ + ".jpg"), "image/jpeg", 82);
            }
            decoded.encode(dir.resolve("whole-" + files++ + ".png"), "image/png", 82);
        }
    }

    private static JpegMeta orientation(int value) {
        return JpegMeta.none().withOrientation(value);
    }

    /** The width and height in the image file's header, as the JDK's reader for its type reads them. */
    private static int[] dimensionsOf(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }
}
