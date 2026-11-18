package org.quarkpress.media.image.imageio.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.testing.mediaimage.ContractImages;
import org.quarkpress.testing.mediaimage.ImageProcessorContract;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Reading, decoding, orienting, cutting, scaling and writing images with ImageIO and the TwelveMonkeys plugins: the
 * shared processor contract, and what only this processor does.
 */
class ImageIoProcessorTest extends ImageProcessorContract {

    private static final int WHITE = 0xFFFFFF;

    private final ImageIoProcessor p = new ImageIoProcessor(100_000_000);

    @Override
    protected ImageProcessor processor(long maxPixels) {
        return new ImageIoProcessor(maxPixels);
    }

    @Override
    protected Set<String> writableTypes() {
        return Set.of("image/jpeg", "image/png", "image/gif");
    }

    /** BMP is read but never written: its copies are JPEG, as WordPress with Imagick writes them. */
    @Override
    protected Set<String> readOnlyTypes() {
        return Set.of("image/bmp");
    }

    @Test
    void bmpIsReadButNotWritten() {
        assertThat(p.supports("image/bmp")).isTrue();
        assertThat(p.writes("image/bmp")).isFalse();
        for (String t : List.of("image/jpeg", "image/png", "image/gif")) {
            assertThat(p.writes(t)).as(t).isTrue();
        }
        assertThat(p.writes("image/webp")).isFalse();
    }

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
    void webpIsNotWritten() throws Exception {
        try (DecodedImage decoded = p.decode(write(MediaFixtures.png(4, 4)), "image/png")) {
            assertThatThrownBy(() -> decoded.encode(dir.resolve("out.webp"), "image/webp", 82))
                    .isInstanceOf(ImageProcessingException.class);
        }
    }

    @Test
    void bmpFilesAreRead() throws Exception {
        assertThat(p.read(write(MediaFixtures.bmp(7, 2)))).contains(new ImageInfo("image/bmp", 7, 2));
    }

    @Test
    void jpegsAreWrittenBaselineAtTheAskedQuality() throws Exception {
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
    void scalingAveragesThePixelsItReplaces() throws Exception {
        // a 1-pixel black and white checkerboard: every averaged output pixel is mid-grey, never black or white
        BufferedImage board = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                board.setRGB(x, y, (x + y) % 2 == 0 ? 0x000000 : WHITE);
            }
        }
        Path target = dir.resolve("board.png");
        try (DecodedImage decoded = p.decode(write(ContractImages.encode(board, "png")), "image/png");
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
        try (DecodedImage decoded = p.decode(write(ContractImages.encode(edge, "png")), "image/png");
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
        Path source = write(ContractImages.cmykJpeg());
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
        try (DecodedImage decoded = p.decode(write(ContractImages.encode(edge, "png")), "image/png")) {
            assertThat(decoded.width()).isEqualTo(65535);
        }
    }

    @Test
    void brokenImagesAreDescribedInSentences() throws Exception {
        byte[] jpeg = MediaFixtures.jpeg(400, 300);
        Path half = write(Arrays.copyOf(jpeg, jpeg.length / 2));
        byte[] png = MediaFixtures.png(400, 300);
        Path halfPng = write(Arrays.copyOf(png, png.length / 2));
        byte[] gif = MediaFixtures.gif(400, 300, 1);
        Path halfGif = write(Arrays.copyOf(gif, gif.length / 2));
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
            Path target = dir.resolve("bomb-" + chunk.length + ".png");
            try (DecodedImage decoded = p.decode(file, "image/png")) { // the chunk is ignored
                assertThat(decoded.width()).isEqualTo(4);
                decoded.encode(target, "image/png", 82);
            }
            assertThat(ContractImages.pngProfile(target)).isNull();
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

    /** A GIF of a 10x10 logical screen whose one frame is 4x4 opaque red at (3, 1). */
    private static byte[] offsetGif() throws IOException {
        // a palette of black and red and raw samples 1: no colour conversion decides the frame's colour
        java.awt.image.IndexColorModel palette = new java.awt.image.IndexColorModel(
                8, 2, new byte[] {0, (byte) 0xFF}, new byte[] {0, 0}, new byte[] {0, 0});
        java.awt.image.WritableRaster samples = palette.createCompatibleWritableRaster(4, 4);
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
            writer.write(streamMetadata, new javax.imageio.IIOImage(frame, null, imageMetadata), null);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static ImageWriter jpegWriter() {
        return ImageIO.getImageWritersByFormatName("jpeg").next();
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
}
