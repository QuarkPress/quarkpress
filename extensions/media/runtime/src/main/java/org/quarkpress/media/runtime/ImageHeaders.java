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
import java.nio.charset.StandardCharsets;
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
 * alone, every other segment skipped), the fixed header fields of the other formats and, of an AVIF, the item
 * boxes that name its primary item's {@code ispe} property and bit depth. Nothing is inflated or parsed beyond those,
 * so a file's metadata cannot make the read expensive.
 */
public final class ImageHeaders {

    /** The type WordPress reports for icons. */
    static final String ICON = "image/vnd.microsoft.icon";

    /** Most bytes read from a JPEG while looking for its frame header, which follows its metadata segments. */
    static final int HEADER_LIMIT = 8 * 1024 * 1024;

    /** Bytes read for the fixed headers of PNG, GIF, BMP, WebP and ICO. */
    static final int PREFIX = 64;

    /** Bytes a WebP's type (lossy, lossless or extended) is read from; a shorter file has none. */
    static final int WEBP_TYPE_BYTES = 40;

    /**
     * Most bytes of an AVIF file read for its primary item's size; a {@code meta} box beyond them (after a large
     * {@code mdat}) gives no size.
     */
    static final int BOX_LIMIT = 1024 * 1024;

    /** The box header count at which an AVIF's reading gives up, as WordPress's does: at most 4095 are read. */
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
            case "image/avif" -> avifSize(file);
            case ICON -> icon(head);
            default -> null;
        };
        if (size == null || !positive(size)) {
            return Optional.empty();
        }
        return Optional.of(new ImageInfo(type, (int) size[0], (int) size[1]));
    }

    /**
     * Whether {@code file} is a lossless WebP, as WordPress tells one: a WebP of at least {@value #WEBP_TYPE_BYTES}
     * bytes whose first chunk is {@code VP8L}. An extended file ({@code VP8X} first: animation, alpha or metadata) is
     * never lossless, whatever its image chunks hold, and neither is any other file.
     *
     * @throws IOException when the file cannot be read
     */
    public static boolean losslessWebp(Path file) throws IOException {
        if (!ImageSignatures.realType(file).map("image/webp"::equals).orElse(false)) {
            return false;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(WEBP_TYPE_BYTES);
        }
        return head.length == WEBP_TYPE_BYTES && isType(head, 12, "VP8L");
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

    private static long[] avifSize(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(BOX_LIMIT);
        }
        return avifSize(head);
    }

    /**
     * The width and height of an AVIF's primary item, read from the boxes of an ISO base media file inside {@code
     * bytes} as WordPress's AVIF reader reads them; null when that reader gives none. See {@link AvifHeader}.
     */
    static long[] avifSize(byte[] bytes) {
        return new AvifHeader(bytes).size();
    }

    /**
     * One reading of an AVIF header, as WordPress 7.1.3 reads one: the file type box must come first and name the
     * brand {@code avif} or {@code avis} among its first 34 four-byte slots (the minor version is not a brand); the
     * first top-level {@code meta} box of version 0 decides. Inside it the primary item ({@code pitm}), the item
     * properties ({@code iprp}: the numbered properties of {@code ipco} and the associations of {@code ipma}) and the
     * derived images' tiles ({@code iref/dimg}) are collected in file order, and after each {@code ipma} and each
     * {@code dimg} box the reading ends with a size when the primary item is associated with an {@code ispe} and with
     * a bit-depth source ({@code pixi} or {@code av1C}), its own or one of its tiles' down to three levels. A tile's
     * size is never the image's, and a size without a bit-depth source is no size.
     *
     * <p>As WordPress does, it keeps at most 9 sizes and 9 bit-depth sources (property numbers restart at 1 in each
     * {@code ipco}), and drops item ids and property numbers above 255. Of {@code ipma} it reads at most 32 entries per
     * box, and associations of items up to 255 until 32 have been read in all its boxes, those dropped for their
     * property number included: once 32 are read, the rest of the box is skipped, before the next entry or the next
     * association. An entry of an item above 255 has its associations read but not counted, and its 33rd ends the
     * box. Of each {@code dimg} it reads at most 16 references and skips the rest; at most 16 tiles are kept in all,
     * and a reference is read (and must be there) even once they are. A {@code pitm} id is read from where the box's
     * content starts even when the box is too short to hold it, as WordPress reads it (from the following bytes); the
     * reading goes on after the box. A box of a version the reader does not know is skipped (it still takes its
     * property number). There is no size once 4096 box headers are read, for a zero width or height, a malformed
     * {@code ispe}, {@code pixi} or {@code av1C}, a primary item id above 255, a 64-bit box size of 4 GiB or more, or a
     * box that does not fit its parent or runs past the bytes there are. Memory is fixed whatever the
     * counts a file declares; nothing is thrown.
     */
    private static final class AvifHeader {

        private static final int MAX_ITEM = 255;
        private static final int MAX_SIZES = 9;
        private static final int MAX_DEPTHS = 9;
        private static final int MAX_ENTRIES = 32;
        private static final int MAX_ASSOCIATIONS = 32;
        private static final int MAX_TILES = 16;
        private static final int TILE_LEVELS = 3;
        private static final int MAX_CHANNELS = 33;
        private static final int BRAND_SLOTS = 34;

        /** A container's end when nothing but the bytes bounds it (the file itself). */
        private static final long OPEN = Long.MAX_VALUE;

        /** The reading cannot go on: the file has no size. */
        private static final class Stop extends Exception {
            private static final Stop INSTANCE = new Stop();

            private Stop() {
                super(null, null, false, false);
            }
        }

        /** A box: its type, where its content starts and ends, and its version and flags (-1 when it has none). */
        private record Box(String type, long content, long end, int version, long flags) {}

        private final byte[] bytes;
        private int headers;
        private long primary = -1;
        private final int[] sizeNumbers = new int[MAX_SIZES];
        private final long[][] sizes = new long[MAX_SIZES][];
        private int sizeCount;
        private final int[] depthNumbers = new int[MAX_DEPTHS];
        private int depthCount;
        private final int[] associationItems = new int[MAX_ASSOCIATIONS];
        private final int[] associationNumbers = new int[MAX_ASSOCIATIONS];
        private int associationCount;
        /** Associations of items up to 255 read in all {@code ipma} boxes, kept or not. */
        private int associationsRead;

        private final int[] tileFrom = new int[MAX_TILES];
        private final int[] tileTo = new int[MAX_TILES];
        private int tileCount;

        AvifHeader(byte[] bytes) {
            this.bytes = bytes;
        }

        long[] size() {
            try {
                return read();
            } catch (Stop unreadable) {
                return null;
            }
        }

        private long[] read() throws Stop {
            Box ftyp = box(0, OPEN);
            if (!ftyp.type().equals("ftyp") || ftyp.end() - ftyp.content() < 8 || !avifBrand(ftyp)) {
                return null;
            }
            long at = ftyp.end();
            while (at < bytes.length) {
                Box box = box(at, OPEN);
                if (box.type().equals("meta") && known(box)) {
                    return meta(box);
                }
                at = box.end();
            }
            return null;
        }

        private boolean avifBrand(Box ftyp) throws Stop {
            long slots = Math.min(BRAND_SLOTS, (ftyp.end() - ftyp.content()) / 4);
            for (int slot = 0; slot < slots; slot++) {
                long at = ftyp.content() + 4L * slot;
                need(at, 4, ftyp.end());
                if (slot != 1 && (isType(bytes, (int) at, "avif") || isType(bytes, (int) at, "avis"))) {
                    return true;
                }
            }
            return false;
        }

        private long[] meta(Box meta) throws Stop {
            for (long at = meta.content(); at < meta.end(); ) {
                Box child = box(at, meta.end());
                if (known(child)) {
                    long[] size = switch (child.type()) {
                        case "pitm" -> primaryItem(child);
                        case "iprp" -> properties(child);
                        case "iref" -> references(child);
                        default -> null;
                    };
                    if (size != null) {
                        return size;
                    }
                }
                at = child.end();
            }
            return null;
        }

        private long[] primaryItem(Box pitm) throws Stop {
            // WordPress takes the id from the following bytes when the box is too short to hold it
            long id = pitm.version() == 0 ? u16(pitm.content(), OPEN) : u32(pitm.content(), OPEN);
            if (id > MAX_ITEM) {
                throw Stop.INSTANCE;
            }
            primary = id;
            return null;
        }

        private long[] properties(Box iprp) throws Stop {
            for (long at = iprp.content(); at < iprp.end(); ) {
                Box child = box(at, iprp.end());
                if (child.type().equals("ipco")) {
                    propertyContainer(child);
                } else if (child.type().equals("ipma") && known(child)) {
                    associations(child);
                    long[] size = complete();
                    if (size != null) {
                        return size;
                    }
                }
                at = child.end();
            }
            return null;
        }

        private void propertyContainer(Box ipco) throws Stop {
            int number = 0;
            for (long at = ipco.content(); at < ipco.end(); ) {
                Box property = box(at, ipco.end());
                number++;
                if (known(property)) {
                    switch (property.type()) {
                        case "ispe" -> extents(property, number);
                        case "pixi" -> pixelInformation(property, number);
                        case "av1C" -> codecConfiguration(property, number);
                        default -> {}
                    }
                }
                at = property.end();
            }
        }

        private void extents(Box ispe, int number) throws Stop {
            long width = u32(ispe.content(), ispe.end());
            long height = u32(ispe.content() + 4, ispe.end());
            if (width == 0 || height == 0) {
                throw Stop.INSTANCE;
            }
            if (number <= MAX_ITEM && sizeCount < MAX_SIZES) {
                sizeNumbers[sizeCount] = number;
                sizes[sizeCount++] = new long[] {width, height};
            }
        }

        private void pixelInformation(Box pixi, int number) throws Stop {
            long channels = u8(pixi.content(), pixi.end());
            if (channels == 0 || channels > MAX_CHANNELS) {
                throw Stop.INSTANCE;
            }
            long depth = u8(pixi.content() + 1, pixi.end());
            for (int channel = 1; channel < channels; channel++) {
                if (u8(pixi.content() + 1 + channel, pixi.end()) != depth) {
                    throw Stop.INSTANCE;
                }
            }
            if (depth == 0) {
                throw Stop.INSTANCE;
            }
            depthSource(number);
        }

        private void codecConfiguration(Box av1C, int number) throws Stop {
            long flags = u8(av1C.content() + 2, av1C.end());
            boolean highBitDepth = (flags & 0x40) != 0;
            boolean twelveBit = (flags & 0x20) != 0;
            if (twelveBit && !highBitDepth) {
                throw Stop.INSTANCE;
            }
            depthSource(number);
        }

        private void depthSource(int number) {
            if (number <= MAX_ITEM && depthCount < MAX_DEPTHS) {
                depthNumbers[depthCount++] = number;
            }
        }

        private void associations(Box ipma) throws Stop {
            boolean wideIds = ipma.version() == 1;
            boolean wideNumbers = (ipma.flags() & 1) != 0;
            long at = ipma.content();
            long entries = u32(at, ipma.end());
            at += 4;
            for (long entry = 0; entry < Math.min(entries, MAX_ENTRIES); entry++) {
                if (associationsRead == MAX_ASSOCIATIONS) {
                    return;
                }
                long item = wideIds ? u32(at, ipma.end()) : u16(at, ipma.end());
                at += wideIds ? 4 : 2;
                long count = u8(at++, ipma.end());
                boolean counted = item <= MAX_ITEM;
                for (long i = 0; i < count; i++) {
                    if (counted ? associationsRead == MAX_ASSOCIATIONS : i == MAX_ASSOCIATIONS) {
                        return;
                    }
                    long number = wideNumbers ? u16(at, ipma.end()) & 0x7FFF : u8(at, ipma.end()) & 0x7F;
                    at += wideNumbers ? 2 : 1;
                    if (counted) {
                        associationsRead++;
                        if (number <= MAX_ITEM) {
                            associationItems[associationCount] = (int) item;
                            associationNumbers[associationCount++] = (int) number;
                        }
                    }
                }
            }
        }

        private long[] references(Box iref) throws Stop {
            for (long at = iref.content(); at < iref.end(); ) {
                Box child = box(at, iref.end());
                if (child.type().equals("dimg")) {
                    tiles(child);
                    long[] size = complete();
                    if (size != null) {
                        return size;
                    }
                }
                at = child.end();
            }
            return null;
        }

        /** The tiles of one derived image; its ids are 16-bit in every version of {@code iref}. */
        private void tiles(Box dimg) throws Stop {
            long from = u16(dimg.content(), dimg.end());
            long count = u16(dimg.content() + 2, dimg.end());
            for (int i = 0; i < Math.min(count, MAX_TILES); i++) {
                long to = u16(dimg.content() + 4 + 2L * i, dimg.end());
                if (from <= MAX_ITEM && to <= MAX_ITEM && tileCount < MAX_TILES) {
                    tileFrom[tileCount] = (int) from;
                    tileTo[tileCount++] = (int) to;
                }
            }
        }

        /** The primary item's size, when it and a bit depth for it are known; null when they are not yet. */
        private long[] complete() {
            if (primary < 0 || sizeCount == 0 || depthCount == 0) {
                return null;
            }
            long[] size = null;
            for (int i = 0; i < associationCount && size == null; i++) {
                if (associationItems[i] == primary) {
                    size = keptSize(associationNumbers[i]);
                }
            }
            if (size == null) {
                return null;
            }
            return hasDepth((int) primary) || tileHasDepth((int) primary, TILE_LEVELS) ? size : null;
        }

        private long[] keptSize(int number) {
            for (int i = 0; i < sizeCount; i++) {
                if (sizeNumbers[i] == number) {
                    return sizes[i];
                }
            }
            return null;
        }

        private boolean hasDepth(int item) {
            for (int i = 0; i < associationCount; i++) {
                if (associationItems[i] == item) {
                    for (int j = 0; j < depthCount; j++) {
                        if (depthNumbers[j] == associationNumbers[i]) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean tileHasDepth(int item, int levels) {
            for (int i = 0; i < tileCount; i++) {
                if (tileFrom[i] == item
                        && (hasDepth(tileTo[i]) || (levels > 1 && tileHasDepth(tileTo[i], levels - 1)))) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the reader knows the box's version: a box of a later version is skipped as free space. */
        private static boolean known(Box box) {
            return switch (box.type()) {
                case "meta", "ispe", "pixi", "auxC" -> box.version() == 0;
                case "pitm", "ipma", "iref" -> box.version() <= 1;
                default -> true;
            };
        }

        private static boolean fullBox(String type) {
            return switch (type) {
                case "meta", "pitm", "ipma", "ispe", "pixi", "iref", "auxC" -> true;
                default -> false;
            };
        }

        /** The box whose header starts at {@code at} in a parent ending at {@code parentEnd}. */
        private Box box(long at, long parentEnd) throws Stop {
            if (++headers >= MAX_BOXES) {
                throw Stop.INSTANCE;
            }
            long size = u32(at, parentEnd);
            String type = new String(bytes, (int) need(at + 4, 4, parentEnd), 4, StandardCharsets.ISO_8859_1);
            long content = at + 8;
            if (size == 1) {
                if (u32(at + 8, parentEnd) != 0) {
                    throw Stop.INSTANCE;
                }
                size = u32(at + 12, parentEnd);
                content = at + 16;
            }
            long end = size == 0 ? parentEnd : at + size;
            if (end < content || end > parentEnd) {
                throw Stop.INSTANCE;
            }
            int version = -1;
            long flags = -1;
            if (fullBox(type)) {
                long word = u32(content, end);
                version = (int) (word >>> 24);
                flags = word & 0xFF_FFFF;
                content += 4;
            }
            return new Box(type, content, end, version, flags);
        }

        /** {@code at}, when {@code length} bytes from it lie inside both the box ending at {@code end} and the bytes. */
        private long need(long at, int length, long end) throws Stop {
            if (at < 0 || at + length > end || at + length > bytes.length) {
                throw Stop.INSTANCE;
            }
            return at;
        }

        private long u8(long at, long end) throws Stop {
            return bytes[(int) need(at, 1, end)] & 0xFFL;
        }

        private long u16(long at, long end) throws Stop {
            int i = (int) need(at, 2, end);
            return ((bytes[i] & 0xFFL) << 8) | (bytes[i + 1] & 0xFFL);
        }

        private long u32(long at, long end) throws Stop {
            return ImageHeaders.u32(bytes, (int) need(at, 4, end));
        }
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
