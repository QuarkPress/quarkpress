package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.image.vips.runtime.VipsFormats;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.ImageHeaders;
import org.quarkpress.testing.mediaimage.ImageProcessorContract;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.SaveOptions;

/**
 * The libvips processor against the shared contract and what only it does: WebP and AVIF copies, HEIC read but never
 * written, first frames of animations, failures without libvips's text, untrusted loaders never reached, and native
 * images always released. The types come from the probe of the installed libvips; a test of a type that libvips lacks
 * is skipped, or fails with {@code -Dvips.require-all-formats=true} (CI).
 */
class VipsImageProcessorDbTest extends ImageProcessorContract {

    static final String AVIF_SKIP =
            "SKIPPED (libvips lacks heifsave with an AV1 encoder): install libheif-plugin-aomenc";
    static final String HEIC_SKIP = "SKIPPED (libvips lacks an HEVC decoder): install libheif-plugin-libde265";
    static final String WEBP_SKIP = "SKIPPED (libvips lacks webpsave): install libvips with libwebp";
    static final String GIF_SKIP = "SKIPPED (libvips lacks gifsave): install libvips with cgif";

    private static final String DECODE_FAILED = "libvips could not decode the image.";

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsImageProcessorDbTest.class)
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkus.libvips.leak-check", "true");

    @Inject
    Libvips libvips;

    VipsFormats formats;

    @BeforeEach
    void probe() {
        formats = VipsFormats.probe(libvips);
    }

    @Override
    protected ImageProcessor processor(long maxPixels) {
        return new VipsImageProcessor(libvips, maxPixels, formats);
    }

    @Override
    protected Set<String> writableTypes() {
        return formats.writable();
    }

    @Override
    protected Set<String> readOnlyTypes() {
        return formats.readable();
    }

    /** As WordPress with Imagick: PNG copies carry no profile. */
    @Override
    protected boolean keepsPngProfile() {
        return false;
    }

    /** As WordPress with either editor: a half JPEG still gets its sizes. */
    @Override
    protected boolean truncatedJpegMakesSizes() {
        return true;
    }

    static boolean requireAll() {
        return Boolean.getBoolean("vips.require-all-formats");
    }

    /**
     * The committed file {@code src/test/resources/images/<name>}, made by us; the HEIC and AVIF images come from
     * {@link MediaFixtures}, which records how they were made.
     *
     * <ul>
     *   <li>{@code animated.webp}: a 16&times;12 animated WebP of three solid frames, red, green and blue, 100 ms
     *       each, looping, no metadata; written by libvips ({@code vips webpsave} of the three frames joined top to
     *       bottom, {@code --page-height 12}).
     *   <li>{@code adobe-cmyk.jpg}: a 64&times;48 Adobe CMYK JPEG (APP14), left half red, right half blue, its CMYK
     *       pixels made with libvips's own CMYK profile, with an embedded {@code CGATS001Compat-v2-micro.icc}
     *       (description "uCMY") from Debian's {@code icc-profiles-free} package (Compact-ICC-Profiles by Clinton
     *       Ingram, CC0-1.0).
     * </ul>
     */
    static Path fixture(String name) {
        return Path.of("src/test/resources/images", name);
    }

    @Test
    void webpIsWrittenAtTheAskedQualityAndLosslessWhenAsked() throws Exception {
        assumeTrue(formats.writes("image/webp") || requireAll(), WEBP_SKIP);
        ImageProcessor p = processor(100_000_000);
        Path lossy = dir.resolve("lossy.webp");
        Path lossyBest = dir.resolve("lossy-100.webp");
        Path lossless = dir.resolve("lossless.webp");
        try (DecodedImage decoded = p.decode(write(noisePng(64, 48)), "image/png")) {
            assertThat(decoded.encode(lossy, "image/webp", 86, false))
                    .isEqualTo(new Encoded("image/webp", 64, 48, Files.size(lossy)));
            decoded.encode(lossyBest, "image/webp", 100, false);
            assertThat(decoded.encode(lossless, "image/webp", 100, true))
                    .isEqualTo(new Encoded("image/webp", 64, 48, Files.size(lossless)));
        }
        assertThat(firstChunk(lossy)).isEqualTo("VP8 ");
        assertThat(ImageHeaders.losslessWebp(lossy)).isFalse();
        assertThat(firstChunk(lossless)).isEqualTo("VP8L");
        assertThat(ImageHeaders.losslessWebp(lossless)).isTrue();
        assertThat(Files.size(lossy)).isLessThan(Files.size(lossyBest));
        // the three-argument form is never lossless
        Path plain = dir.resolve("plain.webp");
        try (DecodedImage decoded = p.decode(write(noisePng(64, 48)), "image/png")) {
            decoded.encode(plain, "image/webp", 100);
        }
        assertThat(firstChunk(plain)).isEqualTo("VP8 ");
    }

    @Test
    void avifSizesAreAvif() throws Exception {
        assumeTrue(formats.writes("image/avif") || requireAll(), AVIF_SKIP);
        ImageProcessor p = processor(100_000_000);
        Path source = write(MediaFixtures.avif(2600, 1800));
        assertThat(p.read(source)).contains(new ImageInfo("image/avif", 2600, 1800));
        Path target = dir.resolve("out.avif");
        try (DecodedImage decoded = p.decode(source, "image/avif");
                DecodedImage region = decoded.region(0, 0, 2600, 1800, 300, 208)) {
            assertThat(region.encode(target, "image/avif", 82))
                    .isEqualTo(new Encoded("image/avif", 300, 208, Files.size(target)));
        }
        byte[] bytes = Files.readAllBytes(target);
        assertThat(new String(bytes, 4, 8, StandardCharsets.ISO_8859_1)).isEqualTo("ftypavif");
        assertThat(p.read(target)).contains(new ImageInfo("image/avif", 300, 208));
        try (Image written = libvips.load(target)) {
            assertThat(written.fields()).doesNotContain("exif-data", "xmp-data", "iptc-data");
        }
    }

    @Test
    void heicIsReadAndDecodedButNeverWritten() throws Exception {
        assumeTrue(formats.decodes("image/heic") || requireAll(), HEIC_SKIP);
        ImageProcessor p = processor(100_000_000);
        assertThat(p.supports("image/heic")).isTrue();
        assertThat(p.supports("image/heif")).isTrue();
        assertThat(p.read(write(MediaFixtures.heic(4032, 3024)))).contains(new ImageInfo("image/heic", 4032, 3024));
        // libheif applies the container's rotation on reading, as WordPress's Imagick sees it
        assertThat(p.read(write(MediaFixtures.heicRotated(4032, 3024))))
                .contains(new ImageInfo("image/heic", 3024, 4032));
        Path jpeg = dir.resolve("out.jpg");
        try (DecodedImage decoded = p.decode(write(MediaFixtures.heic(4032, 3024)), "image/heic");
                DecodedImage region = decoded.region(0, 0, 4032, 3024, 400, 300)) {
            assertThat(region.encode(jpeg, "image/jpeg", 82))
                    .isEqualTo(new Encoded("image/jpeg", 400, 300, Files.size(jpeg)));
            assertThatThrownBy(() -> region.encode(dir.resolve("out.heic"), "image/heic", 82))
                    .isInstanceOf(ImageProcessingException.class)
                    .hasMessage("libvips does not write image/heic.");
            assertThatThrownBy(() -> region.encode(dir.resolve("out.heif"), "image/heif", 82))
                    .isInstanceOf(ImageProcessingException.class)
                    .hasMessage("libvips does not write image/heif.");
        }
        assertThat(p.read(jpeg)).contains(new ImageInfo("image/jpeg", 400, 300));
        assertThat(isBlack(ImageIO.read(jpeg.toFile()))).isFalse();
    }

    @Test
    void aHeicWithoutAnHevcDecoderIsNotReadable() throws Exception {
        if (formats.decodes("image/heic")) {
            // with a decoder, a HEIC decodes to its colours
            Path png = dir.resolve("heic.png");
            try (DecodedImage decoded =
                    processor(100_000_000).decode(write(MediaFixtures.heic(4032, 3024)), "image/heic")) {
                decoded.encode(png, "image/png", 82);
            }
            assertThat(isBlack(ImageIO.read(png.toFile()))).isFalse();
            return;
        }
        assertThat(requireAll())
                .as("HEIC must be readable when every format is required")
                .isFalse();
        assertThat(formats.describe(libvips.version()))
                .contains("image/heic is not read: install libheif-plugin-libde265");
        ImageProcessor p = processor(100_000_000);
        assertThat(p.supports("image/heic")).isFalse();
        assertThat(p.supports("image/heif")).isFalse();
        assertThatThrownBy(() -> p.decode(write(MediaFixtures.heic(4032, 3024)), "image/heic"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("No image processor reads image/heic.");
        if (libvips.hasOperation("heifload") && !libvips.isBlocked("VipsForeignLoadHeifFile")) {
            // why the probe decodes rather than trusting the loader: a small HEIC such as the probe's own decodes
            // without an error, as black pixels
            byte[] probe;
            try (var in = VipsFormats.class.getResourceAsStream("/org/quarkpress/media/image/vips/probe.heic")) {
                probe = in.readAllBytes();
            }
            try (Image heic = libvips.load(probe)) {
                byte[] png = heic.save(SaveOptions.png());
                assertThat(isBlack(ImageIO.read(new ByteArrayInputStream(png)))).isTrue();
            }
        }
    }

    @Test
    void animatedImagesGiveTheirFirstFrame() throws Exception {
        ImageProcessor p = processor(100_000_000);
        // frames: red, blue, red rectangles on white
        try (DecodedImage decoded = p.decode(write(MediaFixtures.gif(40, 30, 3)), "image/gif")) {
            assertThat(decoded.width()).isEqualTo(40);
            assertThat(decoded.height()).isEqualTo(30);
            assertThat(centreOf(decoded)).isEqualTo(0xFF0000);
            if (formats.writes("image/gif")) {
                Path gif = dir.resolve("out.gif");
                decoded.encode(gif, "image/gif", 82);
                assertThat(framesOf(gif)).isEqualTo(1);
            }
        }
        // frames: red, green, blue
        try (DecodedImage decoded = p.decode(fixture("animated.webp"), "image/webp")) {
            assertThat(decoded.width()).isEqualTo(16);
            assertThat(decoded.height()).isEqualTo(12);
            assertThat(centreOf(decoded)).isEqualTo(0xFF0000);
        }
        if (requireAll()) {
            assertThat(formats.writes("image/gif")).as(GIF_SKIP).isTrue();
        }
    }

    @Test
    void supportsIsWhatTheProbeFound() {
        ImageProcessor p = processor(100_000_000);
        assertThat(p.supports("image/jpeg")).isTrue();
        assertThat(p.supports("image/png")).isTrue();
        for (String type : List.of(
                "image/bmp",
                "image/x-icon",
                "image/vnd.microsoft.icon",
                "image/heic-sequence",
                "image/heif-sequence",
                "image/svg+xml",
                "application/pdf")) {
            assertThat(p.supports(type)).as(type).isFalse();
        }
        for (String type : List.of("image/webp", "image/avif", "image/gif", "image/heic", "image/heif", "image/tiff")) {
            assertThat(p.supports(type)).as(type).isEqualTo(formats.supports(type));
        }
        assertThat(processor(100_000_000).supports("image/tiff"))
                .isEqualTo(libvips.hasOperation("tiffload") && libvips.hasOperation("tiffsave_buffer"));
        assertThat(new VipsImageProcessor(libvips, 100_000_000, VipsFormats.NONE).supports("image/jpeg"))
                .isFalse();
    }

    /**
     * As WordPress with Imagick: a truncated PNG or GIF fails at the decode itself (the media module then keeps the
     * upload without sizes), while a truncated JPEG decodes as far as its data goes and gets its sizes.
     */
    @Test
    void aTruncatedPngOrGifFailsAtDecodeAndAJpegGivesSizes() throws Exception {
        ImageProcessor p = processor(100_000_000);
        byte[] png = MediaFixtures.png(400, 300);
        Path halfPng = write(Arrays.copyOf(png, png.length / 2));
        byte[] gif = MediaFixtures.gif(400, 300, 1);
        Path halfGif = write(Arrays.copyOf(gif, gif.length / 2));
        for (Path half : List.of(halfPng, halfGif)) {
            String type = half == halfPng ? "image/png" : "image/gif";
            assertThatThrownBy(() -> p.decode(half, type).close())
                    .as(type)
                    .isInstanceOf(ImageProcessingException.class)
                    .hasMessage(DECODE_FAILED);
        }
        // a truncated PNG declared as a JPEG is not decoded leniently
        assertThatThrownBy(() -> p.decode(halfPng, "image/jpeg").close())
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage(DECODE_FAILED);
        byte[] jpeg = MediaFixtures.jpeg(400, 300);
        Path halfJpeg = write(Arrays.copyOf(jpeg, jpeg.length / 2));
        Path target = dir.resolve("half-150.jpg");
        try (DecodedImage decoded = p.decode(halfJpeg, "image/jpeg");
                DecodedImage region = decoded.region(0, 0, 400, 300, 150, 113)) {
            assertThat(decoded.width()).isEqualTo(400);
            assertThat(decoded.height()).isEqualTo(300);
            assertThat(region.encode(target, "image/jpeg", 82))
                    .isEqualTo(new Encoded("image/jpeg", 150, 113, Files.size(target)));
        }
    }

    @Test
    void failuresNeverCarryLibvipsText() throws Exception {
        ImageProcessor p = processor(100_000_000);
        byte[] png = MediaFixtures.png(400, 300);
        Path half = write(Arrays.copyOf(png, png.length / 2));
        List<Throwable> failures = new ArrayList<>();
        failures.add(catchFailure(() -> p.decode(half, "image/png").close()));
        failures.add(catchFailure(
                () -> p.decode(dir.resolve("missing.jpg"), "image/jpeg").close()));
        failures.add(catchFailure(() ->
                p.decode(write(MediaFixtures.text("not an image")), "image/png").close()));
        assertThat(failures.get(0)).hasMessage(DECODE_FAILED);
        assertThat(failures.get(1)).hasMessage(DECODE_FAILED);
        assertThat(failures.get(2)).hasMessage(DECODE_FAILED);
        List<String> details = new ArrayList<>();
        for (Throwable failure : failures) {
            assertThat(failure).isInstanceOf(ImageProcessingException.class);
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof LibvipsException libvipsFailure
                        && !libvipsFailure.detail().isBlank()) {
                    details.add(libvipsFailure.detail());
                }
            }
        }
        assertThat(details).as("libvips explained at least one failure").isNotEmpty();
        for (Throwable failure : failures) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                String message = String.valueOf(t.getMessage());
                assertThat(message).doesNotContain(dir.toString(), "Premature", "VipsJpeg");
                for (String detail : details) {
                    assertThat(message).doesNotContain(detail.strip());
                }
            }
        }
    }

    @Test
    void untrustedLoadersAreNeverReached() throws Exception {
        ImageProcessor p = processor(100_000_000);
        assertThat(libvips.isBlocked("ppmload")).isTrue();
        if (libvips.hasOperation("svgload")) {
            assertThat(libvips.isBlocked("svgload")).isTrue();
        }
        byte[] ppm = concat("P6\n4 4\n255\n".getBytes(StandardCharsets.US_ASCII), new byte[48]);
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\">"
                        + "<rect width=\"4\" height=\"4\" fill=\"red\"/></svg>")
                .getBytes(StandardCharsets.UTF_8);
        // libvips does not block its matrix loader: the decode refuses what it reads by type
        byte[] matrix = "2 2\n1 2\n3 4\n".getBytes(StandardCharsets.US_ASCII);
        for (byte[] bytes : List.of(ppm, svg, matrix)) {
            Path file = write(bytes);
            assertThat(p.read(file)).isEmpty();
            assertThatThrownBy(() -> p.decode(file, "image/png").close())
                    .isInstanceOf(ImageProcessingException.class)
                    .hasMessage(DECODE_FAILED);
        }
    }

    /** A TIFF is measured but never decoded: its sizes are not made, as for any type that is not displayable. */
    @Test
    void aTiffIsReadButNotDecoded() throws Exception {
        assumeTrue(formats.supports("image/tiff") || requireAll(), "SKIPPED (libvips lacks tiffload)");
        ImageProcessor p = processor(100_000_000);
        Path tiff = write(MediaFixtures.tiff(30, 20));
        assertThat(p.read(tiff)).contains(new ImageInfo("image/tiff", 30, 20));
        assertThatThrownBy(() -> p.decode(tiff, "image/tiff"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("No image processor reads image/tiff.");
        // a TIFF declared as a type that is decoded is refused by its real type
        assertThatThrownBy(() -> p.decode(tiff, "image/png").close())
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage(DECODE_FAILED);
    }

    @Test
    void anAdobeCmykJpegWithItsProfileIsDecodedToRgbColours() throws Exception {
        ImageProcessor p = processor(100_000_000);
        Path target = dir.resolve("out.jpg");
        try (DecodedImage decoded = p.decode(fixture("adobe-cmyk.jpg"), "image/jpeg")) {
            assertThat(decoded.width()).isEqualTo(64);
            assertThat(decoded.height()).isEqualTo(48);
            decoded.encode(target, "image/jpeg", 90);
        }
        try (Image written = libvips.load(target)) {
            assertThat(written.bands()).isEqualTo(3);
            assertThat(written.interpretation()).isEqualTo("srgb");
        }
        BufferedImage out = ImageIO.read(target.toFile());
        // the left half is red, the right half blue (an inverted Adobe CMYK read would give cyan and yellow)
        int left = out.getRGB(8, 24);
        int right = out.getRGB(56, 24);
        assertThat(red(left)).isGreaterThan(160);
        assertThat(green(left)).isLessThan(100);
        assertThat(blue(left)).isLessThan(100);
        assertThat(blue(right)).isGreaterThan(120);
        assertThat(red(right)).isLessThan(100);
    }

    @Test
    void transparentPixelsLendNoColour() throws Exception {
        // transparent red on the left, opaque blue on the right
        BufferedImage source = new BufferedImage(8, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 8; x++) {
                source.setRGB(x, y, x < 4 ? 0x00FF0000 : 0xFF0000FF);
            }
        }
        Path target = dir.resolve("out.png");
        try (DecodedImage decoded = processor(100_000_000).decode(write(png(source)), "image/png");
                DecodedImage region = decoded.region(0, 0, 8, 2, 3, 1)) {
            region.encode(target, "image/png", 82);
        }
        BufferedImage out = ImageIO.read(target.toFile());
        for (int x = 0; x < 3; x++) {
            int argb = out.getRGB(x, 0);
            if (argb >>> 24 > 0) {
                assertThat(red(argb)).as("red at %d of %08x", x, argb).isLessThan(16);
            }
        }
    }

    /** As the ImageIO processor writes it: the alpha channel is dropped and the colours stay as stored. */
    @Test
    void aJpegOfAnImageWithAlphaDropsTheAlpha() throws Exception {
        BufferedImage source = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 8; x++) {
                source.setRGB(x, y, x < 4 ? 0x00FF0000 : 0xFF0000FF);
            }
        }
        Path target = dir.resolve("out.jpg");
        try (DecodedImage decoded = processor(100_000_000).decode(write(png(source)), "image/png")) {
            assertThat(decoded.encode(target, "image/jpeg", 95))
                    .isEqualTo(new Encoded("image/jpeg", 8, 6, Files.size(target)));
        }
        BufferedImage out = ImageIO.read(target.toFile());
        assertThat(out.getColorModel().hasAlpha()).isFalse();
        assertThat(red(out.getRGB(0, 0))).isGreaterThan(200);
        assertThat(blue(out.getRGB(7, 0))).isGreaterThan(200);
    }

    /** A half-opaque 16-bit colour keeps its 16-bit samples, colour and alpha, through scaling. */
    @Test
    void aSixteenBitImageWithAlphaIsScaledInSixteenBits() throws Exception {
        BufferedImage halfRed = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 8; x++) {
                halfRed.setRGB(x, y, 0x80FF0000); // red 255, alpha 128
            }
        }
        byte[] sixteen;
        // red 255 * 257 = 65535, alpha 128 * 256 = 32768
        try (Image source = libvips.load(png(halfRed));
                Image scaled =
                        source.call("linear", Map.of("a", new double[] {257, 257, 257, 256}, "b", new double[] {0}));
                Image wide = scaled.call("cast", Map.of("format", "ushort"));
                Image tagged = wide.call("copy", Map.of("interpretation", "rgb16"))) {
            sixteen = tagged.save(SaveOptions.png());
        }
        Path target = dir.resolve("out.png");
        try (DecodedImage decoded = processor(100_000_000).decode(write(sixteen), "image/png");
                DecodedImage region = decoded.region(0, 0, 8, 6, 3, 2)) {
            region.encode(target, "image/png", 82);
        }
        try (Image written = libvips.load(target)) {
            assertThat(written.bands()).isEqualTo(4);
            assertThat(written.field("format"))
                    .hasValueSatisfying(f -> assertThat(f).contains("USHORT"));
        }
        // the JDK's PNG reader keeps the 16-bit samples
        Raster raster = ImageIO.read(target.toFile()).getRaster();
        for (int x = 0; x < 3; x++) {
            assertThat(raster.getSample(x, 1, 0)).as("red at %d", x).isCloseTo(65535, within(2));
            assertThat(raster.getSample(x, 1, 1)).as("green at %d", x).isLessThanOrEqualTo(2);
            assertThat(raster.getSample(x, 1, 3)).as("alpha at %d", x).isCloseTo(32768, within(2));
        }
    }

    /**
     * An image wider than libvips's largest shrink factor (1 000 000) is still decoded whole and gives its sizes, as
     * WordPress makes them.
     */
    @Test
    void anImageWiderThanAMillionPixelsIsDecoded() throws Exception {
        Path wide = write(MediaFixtures.png(1_000_001, 2));
        Path target = dir.resolve("wide.png");
        try (DecodedImage decoded = processor(100_000_000).decode(wide, "image/png");
                DecodedImage region = decoded.region(0, 0, 1_000_001, 2, 300, 1)) {
            assertThat(decoded.width()).isEqualTo(1_000_001);
            assertThat(region.encode(target, "image/png", 82))
                    .isEqualTo(new Encoded("image/png", 300, 1, Files.size(target)));
        }
    }

    /** Copies are written only in the types written here: never the types only read or accepted. */
    @Test
    void writesIsTheWrittenTypesOnly() {
        ImageProcessor p = processor(100_000_000);
        for (String type : formats.writable()) {
            assertThat(p.writes(type)).as(type).isTrue();
        }
        for (String type : List.of("image/heic", "image/heif", "image/tiff", "image/bmp")) {
            assertThat(p.writes(type)).as(type).isFalse();
        }
        if (formats.supports("image/tiff")) {
            assertThat(p.supports("image/tiff")).isTrue();
        }
    }

    @Test
    void everyImageIsClosedEvenWhenEncodingFails() throws Exception {
        long before = libvips.openImages();
        ImageProcessor p = processor(100_000_000);
        Path source = write(MediaFixtures.jpeg(400, 300, JpegMeta.none().withOrientation(6)));
        DecodedImage decoded = p.decode(source, "image/jpeg");
        DecodedImage turned = null;
        DecodedImage region = null;
        try {
            turned = decoded.oriented(5);
            region = turned.region(10, 10, 200, 100, 20, 10);
            DecodedImage cut = region;
            assertThatThrownBy(() -> cut.encode(dir.resolve("out.heic"), "image/heic", 82))
                    .isInstanceOf(ImageProcessingException.class);
            assertThat(libvips.openImages()).isGreaterThan(before);
        } finally {
            for (DecodedImage image : new DecodedImage[] {region, turned, decoded}) {
                if (image != null) {
                    image.close();
                }
            }
        }
        assertThat(libvips.openImages()).isEqualTo(before);
        // failed decodes leave nothing open either
        byte[] png = MediaFixtures.png(400, 300);
        Path half = write(Arrays.copyOf(png, png.length / 2));
        Path text = write(MediaFixtures.text("x"));
        Path big = write(MediaFixtures.png(400, 300));
        catchFailure(() -> p.decode(half, "image/png"));
        catchFailure(() -> p.decode(text, "image/png"));
        catchFailure(() -> processor(1000).decode(big, "image/png"));
        assertThat(libvips.openImages()).isEqualTo(before);
        assertThat(libvips.leakedImages()).isZero();
    }

    @Test
    void imagesAboveTheProcessorsLimitButWithinLibvipsAreNotDecoded() throws Exception {
        assertThatThrownBy(() -> processor(400L * 300 - 1).decode(write(MediaFixtures.png(400, 300)), "image/png"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("The image is too large to process: 400x300 pixels.");
        try (DecodedImage decoded = processor(400L * 300).decode(write(MediaFixtures.png(400, 300)), "image/png")) {
            assertThat(decoded.width()).isEqualTo(400);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private interface Failing {
        void run() throws Exception;
    }

    private static Throwable catchFailure(Failing call) {
        try {
            call.run();
        } catch (Exception failure) {
            return failure;
        }
        throw new AssertionError("expected a failure");
    }

    private int centreOf(DecodedImage image) throws Exception {
        Path png = dir.resolve("centre-" + System.nanoTime() + ".png");
        image.encode(png, "image/png", 82);
        BufferedImage out = ImageIO.read(png.toFile());
        return out.getRGB(out.getWidth() / 2, out.getHeight() / 2) & 0xFFFFFF;
    }

    private static int framesOf(Path gif) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isBlack(BufferedImage image) {
        Raster raster = image.getRaster();
        for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 16)) {
            for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 16)) {
                for (int band = 0; band < Math.min(3, raster.getNumBands()); band++) {
                    if (raster.getSample(x, y, band) > 8) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The first chunk's four-character code of a WebP file. */
    private static String firstChunk(Path webp) throws IOException {
        byte[] bytes = Files.readAllBytes(webp);
        assertThat(new String(bytes, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4, StandardCharsets.ISO_8859_1)).isEqualTo("WEBP");
        return new String(bytes, 12, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] noisePng(int w, int h) throws IOException {
        Random random = new Random(42);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return png(image);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
