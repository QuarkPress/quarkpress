package org.quarkpress.media.runtime;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.jpeg.JpegReader;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.quarkpress.media.ImageInfo;

/**
 * The stored dimensions and type of an image, read from its file header without decoding any pixels and without an
 * image processor, as WordPress reads them: JPEG, PNG, GIF, BMP, WebP, ICO and AVIF. HEIC and HEIF images give
 * nothing (WordPress keeps only their file size when no editor decodes them), nor does any other file.
 *
 * <p>Only the record holding the dimensions is read: a JPEG's frame header (through metadata-extractor's frame reader
 * alone, every other segment skipped), the fixed header fields of the other formats and an AVIF's {@code ispe}
 * property. Nothing is inflated or parsed beyond those, so a file's metadata cannot make the read expensive.
 */
public final class ImageHeaders {

    /** The type WordPress reports for icons. */
    static final String ICON = "image/vnd.microsoft.icon";

    /** Most bytes read from a JPEG while looking for its frame header, which follows its metadata segments. */
    static final int HEADER_LIMIT = 8 * 1024 * 1024;

    /** Bytes read for the fixed headers of PNG, GIF, BMP, WebP and ICO. */
    static final int PREFIX = 64;

    /** Most bytes of an AVIF file searched for the {@code ispe} property, and most boxes visited. */
    static final int BOX_LIMIT = 1024 * 1024;

    static final int MAX_BOXES = 4096;

    private ImageHeaders() {}

    /**
     * The raw stored dimensions (never rotated) and the type of {@code file}.
     *
     * @return empty for HEIC/HEIF, for any type other than JPEG, PNG, GIF, BMP, WebP, ICO and AVIF, and for a header
     *     that cannot be read or gives no positive dimensions
     * @throws IOException when the file cannot be read
     */
    public static Optional<ImageInfo> read(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(PREFIX);
        }
        String type = ImageSignatures.realType(file).orElse(null);
        if (type == null && isIcon(head)) {
            type = ICON;
        }
        if (type == null) {
            return Optional.empty();
        }
        long[] size = switch (type) {
            case "image/jpeg" -> jpeg(file);
            case "image/png" -> png(head);
            case "image/gif" -> gif(head);
            case "image/bmp" -> bmp(head);
            case "image/webp" -> webp(head);
            case "image/avif" -> ispe(file);
            case ICON -> icon(head);
            default -> null;
        };
        if (size == null || !positive(size)) {
            return Optional.empty();
        }
        return Optional.of(new ImageInfo(type, (int) size[0], (int) size[1]));
    }

    private static boolean positive(long[] size) {
        return size[0] > 0 && size[1] > 0 && size[0] <= Integer.MAX_VALUE && size[1] <= Integer.MAX_VALUE;
    }

    /** The frame header's dimensions; every other segment is skipped unread. */
    private static long[] jpeg(Path file) throws IOException {
        Metadata metadata;
        try (InputStream in = new Bounded(Files.newInputStream(file), HEADER_LIMIT)) {
            metadata = JpegMetadataReader.readMetadata(in, List.of(new JpegReader()));
        } catch (JpegProcessingException | EOFException | RuntimeException unreadable) {
            return null;
        }
        JpegDirectory frame = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (frame == null) {
            return null;
        }
        Long width = frame.getLongObject(JpegDirectory.TAG_IMAGE_WIDTH);
        Long height = frame.getLongObject(JpegDirectory.TAG_IMAGE_HEIGHT);
        return width == null || height == null ? null : new long[] {width, height};
    }

    /** The {@code IHDR} chunk, which a PNG holds first. */
    static long[] png(byte[] head) {
        if (head.length < 24 || u32(head, 8) != 13 || !isType(head, 12, "IHDR")) {
            return null;
        }
        return new long[] {u32(head, 16), u32(head, 20)};
    }

    /** The logical screen size of a GIF. */
    static long[] gif(byte[] head) {
        if (head.length < 10) {
            return null;
        }
        return new long[] {u16le(head, 6), u16le(head, 8)};
    }

    /** A BMP's info header: 16-bit sizes in the old 12-byte header, signed 32-bit ones (negative = top-down) else. */
    static long[] bmp(byte[] head) {
        if (head.length < 26) {
            return null;
        }
        long headerSize = u32le(head, 14);
        if (headerSize == 12) {
            return new long[] {u16le(head, 18), u16le(head, 20)};
        }
        return new long[] {Math.abs((long) (int) u32le(head, 18)), Math.abs((long) (int) u32le(head, 22))};
    }

    /** The first chunk of a WebP: lossy key frame, lossless or extended header. */
    static long[] webp(byte[] head) {
        if (head.length < 30) {
            return null;
        }
        if (isType(head, 12, "VP8 ")) {
            if ((head[23] & 0xFF) != 0x9D || (head[24] & 0xFF) != 0x01 || (head[25] & 0xFF) != 0x2A) {
                return null;
            }
            return new long[] {u16le(head, 26) & 0x3FFF, u16le(head, 28) & 0x3FFF};
        }
        if (isType(head, 12, "VP8L")) {
            if ((head[20] & 0xFF) != 0x2F) {
                return null;
            }
            long bits = u32le(head, 21);
            return new long[] {(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1};
        }
        if (isType(head, 12, "VP8X")) {
            return new long[] {u24le(head, 24) + 1, u24le(head, 27) + 1};
        }
        return null;
    }

    /** Whether the bytes start an icon file: reserved 0, type 1, at least one image. */
    static boolean isIcon(byte[] head) {
        return head.length >= 22 && head[0] == 0 && head[1] == 0 && head[2] == 1 && head[3] == 0 && u16le(head, 4) > 0;
    }

    /** The first icon of the file; a stored 0 means 256 pixels. */
    static long[] icon(byte[] head) {
        long width = head[6] & 0xFF;
        long height = head[7] & 0xFF;
        return new long[] {width == 0 ? 256 : width, height == 0 ? 256 : height};
    }

    private static long[] ispe(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(BOX_LIMIT);
        }
        return ispe(head);
    }

    /**
     * The first {@code ispe} (image spatial extents) property of an ISO base media file's {@code meta} box, found by
     * walking {@code meta}, {@code iprp} and {@code ipco} inside {@code bytes}; null when there is none.
     */
    static long[] ispe(byte[] bytes) {
        int[] visited = {0};
        int meta = child(bytes, 0, bytes.length, "meta", visited);
        if (meta < 0) {
            return null;
        }
        // meta is a full box: four bytes of version and flags precede its children
        int metaEnd = boxEnd(bytes, meta, bytes.length);
        int iprp = child(bytes, contentStart(bytes, meta) + 4, metaEnd, "iprp", visited);
        if (iprp < 0) {
            return null;
        }
        int iprpEnd = boxEnd(bytes, iprp, metaEnd);
        int ipco = child(bytes, contentStart(bytes, iprp), iprpEnd, "ipco", visited);
        if (ipco < 0) {
            return null;
        }
        int ipcoEnd = boxEnd(bytes, ipco, iprpEnd);
        int ispe = child(bytes, contentStart(bytes, ipco), ipcoEnd, "ispe", visited);
        if (ispe < 0) {
            return null;
        }
        int at = contentStart(bytes, ispe) + 4;
        if (at + 8 > boxEnd(bytes, ispe, ipcoEnd)) {
            return null;
        }
        return new long[] {u32(bytes, at), u32(bytes, at + 4)};
    }

    /** The offset of the first box of {@code type} among the boxes from {@code from} to {@code to}, or -1. */
    private static int child(byte[] bytes, int from, int to, String type, int[] visited) {
        int at = from;
        while (at >= 0 && at + 8 <= to && visited[0]++ < MAX_BOXES) {
            long size = boxSize(bytes, at, to);
            if (size < 8 || at + size > to) {
                return -1;
            }
            if (isType(bytes, at + 4, type)) {
                return at;
            }
            at += (int) size;
        }
        return -1;
    }

    /** A box's size: its 32-bit size, its 64-bit size when that is 1, or the rest of its parent when it is 0. */
    private static long boxSize(byte[] bytes, int at, int parentEnd) {
        long size = u32(bytes, at);
        if (size == 1) {
            if (at + 16 > parentEnd) {
                return -1;
            }
            long large = (u32(bytes, at + 8) << 32) | u32(bytes, at + 12);
            return large < 16 ? -1 : large;
        }
        return size == 0 ? parentEnd - at : size;
    }

    private static int contentStart(byte[] bytes, int at) {
        return at + (u32(bytes, at) == 1 ? 16 : 8);
    }

    /** The end of the box at {@code at} inside a parent ending at {@code parentEnd}. */
    private static int boxEnd(byte[] bytes, int at, int parentEnd) {
        long size = boxSize(bytes, at, parentEnd);
        return (int) Math.min(parentEnd, at + size);
    }

    private static boolean isType(byte[] bytes, int at, String type) {
        for (int i = 0; i < 4; i++) {
            if (bytes[at + i] != type.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long u16le(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL) | ((bytes[at + 1] & 0xFFL) << 8);
    }

    private static long u24le(byte[] bytes, int at) {
        return u16le(bytes, at) | ((bytes[at + 2] & 0xFFL) << 16);
    }

    private static long u32le(byte[] bytes, int at) {
        return u24le(bytes, at) | ((bytes[at + 3] & 0xFFL) << 24);
    }

    private static long u32(byte[] bytes, int at) {
        return ((bytes[at] & 0xFFL) << 24)
                | ((bytes[at + 1] & 0xFFL) << 16)
                | ((bytes[at + 2] & 0xFFL) << 8)
                | (bytes[at + 3] & 0xFFL);
    }

    /** A stream that ends after {@code limit} bytes, so no reader takes more of a file than a header needs. */
    static final class Bounded extends FilterInputStream {

        private long remaining;

        Bounded(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
