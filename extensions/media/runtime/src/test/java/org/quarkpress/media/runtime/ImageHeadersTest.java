package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

class ImageHeadersTest {

    private static Path write(byte[] bytes) throws IOException {
        return ImageMetaReaderTest.write(bytes);
    }

    private static JpegMeta orientation(int value) {
        return JpegMeta.none().withOrientation(value);
    }

    @Test
    void headerDimensionsWithoutAProcessor() throws IOException {
        assertThat(ImageHeaders.read(write(MediaFixtures.jpeg(64, 48, orientation(6)))))
                .contains(new ImageInfo("image/jpeg", 64, 48));
        assertThat(ImageHeaders.read(write(MediaFixtures.png(8, 8)))).contains(new ImageInfo("image/png", 8, 8));
        assertThat(ImageHeaders.read(write(MediaFixtures.gif(30, 20, 3)))).contains(new ImageInfo("image/gif", 30, 20));
        assertThat(ImageHeaders.read(write(MediaFixtures.bmp(5, 4)))).contains(new ImageInfo("image/bmp", 5, 4));
        assertThat(ImageHeaders.read(write(MediaFixtures.webp()))).contains(new ImageInfo("image/webp", 24, 16));
        assertThat(ImageHeaders.read(write(MediaFixtures.pngHeaderOnly(40000, 40000))))
                .contains(new ImageInfo("image/png", 40000, 40000));
        assertThat(ImageHeaders.read(write(MediaFixtures.heif("heic")))).isEmpty();
        assertThat(ImageHeaders.read(write(MediaFixtures.heif("heif")))).isEmpty();
        assertThat(ImageHeaders.read(write(MediaFixtures.heif("mif1")))).isEmpty();
        assertThat(ImageHeaders.read(write(MediaFixtures.text("x")))).isEmpty();
        assertThat(ImageHeaders.read(write(MediaFixtures.heif("avif"))))
                .contains(new ImageInfo("image/avif", 3000, 2000));
        assertThat(ImageHeaders.read(write(MediaFixtures.ico()))).contains(new ImageInfo(ImageHeaders.ICON, 16, 16));
        assertThat(ImageHeaders.read(write(MediaFixtures.pdf()))).isEmpty();
        assertThat(ImageHeaders.read(write(MediaFixtures.psd()))).isEmpty();
    }

    @Test
    void aCutOffImageHasNoDimensions() throws IOException {
        byte[] jpeg = MediaFixtures.jpeg(64, 48);
        assertThat(ImageHeaders.read(write(Arrays.copyOf(jpeg, 12)))).isEmpty();
        byte[] png = MediaFixtures.png(8, 8);
        assertThat(ImageHeaders.read(write(Arrays.copyOf(png, 16)))).isEmpty();
    }

    @Test
    void theIspeWalkReadsAnAvifHeader() {
        assertThat(ImageHeaders.ispe(MediaFixtures.heif("avif"))).containsExactly(3000, 2000);
        assertThat(ImageHeaders.ispe(new byte[0])).isNull();
        byte[] cut = Arrays.copyOf(MediaFixtures.heif("avif"), 60);
        assertThat(ImageHeaders.ispe(cut)).isNull();
        // a box claiming more bytes than its parent holds ends the walk
        byte[] broken = MediaFixtures.heif("avif");
        broken[0] = 0x7F;
        assertThat(ImageHeaders.ispe(broken)).isNull();
    }

    // ---- hostile and unusual headers -------------------------------------------------------------------------------

    static final Duration QUICK = Duration.ofSeconds(5);

    static byte[] pngChunk(String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length)
                .putInt(data.length)
                .put(name)
                .put(data)
                .putInt((int) crc.getValue())
                .array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    static byte[] ihdr(int w, int h) {
        return pngChunk(
                "IHDR",
                ByteBuffer.allocate(13)
                        .putInt(w)
                        .putInt(h)
                        .put(new byte[] {8, 2, 0, 0, 0})
                        .array());
    }

    /** {@code megabytes} MiB of zeros, deflated (about a thousandth of that on disk). */
    static byte[] deflatedZeros(int megabytes) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        byte[] zeros = new byte[1 << 20];
        byte[] buffer = new byte[64 * 1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < megabytes; i++) {
            deflater.setInput(zeros);
            while (!deflater.needsInput()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** An 8x8 PNG whose {@code zTXt} (or {@code iCCP}) chunk inflates to 1 GiB. */
    static byte[] pngBomb(String chunk) {
        byte[] keyword = (chunk.equals("iCCP") ? "ICC" : "Comment").getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = concat(keyword, new byte[] {0, 0}, deflatedZeros(1024));
        return concat(PNG_SIGNATURE, ihdr(8, 8), pngChunk(chunk, body), pngChunk("IEND", new byte[0]));
    }

    /** An 8x8 PNG followed by a {@code tEXt} chunk that declares almost 2 GiB and holds a few bytes. */
    static byte[] pngHugeDeclaredChunk() {
        byte[] header = ByteBuffer.allocate(8)
                .putInt(0x7FFF_FFF0)
                .put("tEXt".getBytes(StandardCharsets.ISO_8859_1))
                .array();
        return concat(PNG_SIGNATURE, ihdr(8, 8), header, "Comment\0x".getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void compressedPngChunksAreNeverInflated() throws IOException {
        for (String chunk : new String[] {"zTXt", "iCCP"}) {
            Path bomb = write(pngBomb(chunk));
            assertThat(assertTimeoutPreemptively(QUICK, () -> ImageHeaders.read(bomb)))
                    .as(chunk)
                    .contains(new ImageInfo("image/png", 8, 8));
        }
        Path huge = write(pngHugeDeclaredChunk());
        assertThat(assertTimeoutPreemptively(QUICK, () -> ImageHeaders.read(huge)))
                .contains(new ImageInfo("image/png", 8, 8));
    }

    @Test
    void headerEdges() throws IOException {
        byte[] icon = MediaFixtures.ico();
        icon[6] = 0;
        icon[7] = 0;
        assertThat(ImageHeaders.read(write(icon))).contains(new ImageInfo(ImageHeaders.ICON, 256, 256));
        byte[] bmp = MediaFixtures.bmp(5, 4);
        ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).putInt(22, -4);
        assertThat(ImageHeaders.read(write(bmp))).contains(new ImageInfo("image/bmp", 5, 4));
        byte[] wide = concat(PNG_SIGNATURE, ihdr(0x8000_0000, 8), pngChunk("IEND", new byte[0]));
        assertThat(ImageHeaders.read(write(wide))).isEmpty();
        byte[] zero = concat(PNG_SIGNATURE, ihdr(0, 8), pngChunk("IEND", new byte[0]));
        assertThat(ImageHeaders.read(write(zero))).isEmpty();
        byte[] gif = MediaFixtures.gif(30, 20, 1);
        gif[6] = 99; // the logical screen width, which PHP reports
        assertThat(ImageHeaders.read(write(gif))).contains(new ImageInfo("image/gif", 99, 20));
        assertThat(ImageHeaders.read(write(webp("VP8X", vp8x(300, 200)))))
                .contains(new ImageInfo("image/webp", 300, 200));
        assertThat(ImageHeaders.read(write(webp("VP8 ", vp8(640, 480)))))
                .contains(new ImageInfo("image/webp", 640, 480));
    }

    /** A RIFF WebP holding one chunk. */
    static byte[] webp(String chunk, byte[] data) {
        ByteBuffer file = ByteBuffer.allocate(20 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(12 + data.length);
        file.put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(chunk.getBytes(StandardCharsets.ISO_8859_1));
        file.putInt(data.length).put(data);
        return file.array();
    }

    /** The extended-format header: flags, then the canvas size minus one in 24 bits each. */
    static byte[] vp8x(int w, int h) {
        ByteBuffer data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0);
        data.put((byte) (w - 1)).put((byte) ((w - 1) >> 8)).put((byte) ((w - 1) >> 16));
        data.put((byte) (h - 1)).put((byte) ((h - 1) >> 8)).put((byte) ((h - 1) >> 16));
        return data.array();
    }

    /** A lossy key frame header: frame tag, start code, 14-bit width and height. */
    static byte[] vp8(int w, int h) {
        ByteBuffer data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {0x10, 0x02, 0x00, (byte) 0x9D, 0x01, 0x2A});
        data.putShort((short) w).putShort((short) h);
        return data.array();
    }

    @Test
    void theIspeWalkHandlesLargeAndOpenEndedBoxes() {
        byte[] avif = MediaFixtures.heif("avif");
        int ftyp = ByteBuffer.wrap(avif).getInt(0);
        // the same file with a 64-bit size on its meta box
        byte[] meta = Arrays.copyOfRange(avif, ftyp, avif.length);
        int metaSize = ByteBuffer.wrap(meta).getInt(0);
        byte[] large = concat(
                Arrays.copyOf(avif, ftyp),
                ByteBuffer.allocate(16)
                        .putInt(1)
                        .put("meta".getBytes(StandardCharsets.ISO_8859_1))
                        .putLong(metaSize + 8L)
                        .array(),
                Arrays.copyOfRange(meta, 8, metaSize),
                Arrays.copyOfRange(meta, metaSize, meta.length));
        assertThat(ImageHeaders.ispe(large)).containsExactly(3000, 2000);
        // a meta box of size 0 runs to the end of the file
        byte[] open = concat(Arrays.copyOf(avif, ftyp), Arrays.copyOf(meta, metaSize));
        ByteBuffer.wrap(open).putInt(ftyp, 0);
        assertThat(ImageHeaders.ispe(open)).containsExactly(3000, 2000);
        // more boxes than the walk visits before the meta box
        byte[] free = ByteBuffer.allocate(8)
                .putInt(8)
                .put("free".getBytes(StandardCharsets.ISO_8859_1))
                .array();
        byte[][] parts = new byte[ImageHeaders.MAX_BOXES + 2][];
        Arrays.fill(parts, free);
        parts[parts.length - 1] = meta;
        assertThat(ImageHeaders.ispe(concat(parts))).isNull();
        parts = Arrays.copyOfRange(parts, ImageHeaders.MAX_BOXES / 2, parts.length);
        assertThat(ImageHeaders.ispe(concat(parts))).containsExactly(3000, 2000);
    }

    @Test
    void theOldBmpCoreHeaderHasSixteenBitSizes() {
        byte[] head = new byte[26];
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, (byte) 'B')
                .put(1, (byte) 'M')
                .putInt(14, 12)
                .putShort(18, (short) 7)
                .putShort(20, (short) 3);
        buffer.putShort(22, (short) 1).putShort(24, (short) 24);
        assertThat(ImageHeaders.bmp(head)).containsExactly(7, 3);
        buffer.putInt(14, 40).putInt(18, 7).putInt(22, -3);
        assertThat(ImageHeaders.bmp(head)).containsExactly(7, 3);
    }

    @Test
    void anIconNeedsAtLeastOneImage() {
        byte[] head = Arrays.copyOf(MediaFixtures.ico(), 22);
        assertThat(ImageHeaders.isIcon(head)).isTrue();
        head[4] = 0;
        assertThat(ImageHeaders.isIcon(head)).isFalse();
        head[3] = 1;
        head[4] = 1;
        assertThat(ImageHeaders.isIcon(head)).isFalse();
    }
}
