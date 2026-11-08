package org.quarkpress.media.runtime;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The boxes of an MP4 or QuickTime file that state its duration and describe its tracks, copied into a new box
 * sequence for metadata-extractor to read. A box parser allocates the size a box declares before reading it, so it is
 * never given the file itself: every box copied here was checked to lie inside its parent (and so inside the file),
 * nothing else of the file is copied, and the copy holds at most {@value #LIMIT} bytes of box contents.
 *
 * <p>Copied: {@code ftyp} and {@code moov} at the top level; inside them the boxes on the way to the track
 * descriptions ({@code moov}, {@code trak}, {@code mdia}, {@code minf}, {@code stbl}), whose children are checked in
 * turn, and the movie, track and media headers, the handler, the media information header, the sample descriptions
 * and the time-to-sample table. A box whose declared size does not fit in its parent ends the reading of that parent:
 * what came before it is kept. A sample description or time-to-sample table whose entry count does not fit in its box
 * is left out, since the readers size their lists by the count. Each copied box gets a plain 32-bit size, whatever
 * form the file used.
 */
final class IsoBoxes {

    /** The most bytes of box contents copied; a box that would go beyond is left out. */
    static final int LIMIT = 32 << 20;

    /** The most boxes looked at in one file; a file with more gives no copy. */
    private static final int MAX_BOXES = 100_000;

    /** The deepest nesting followed (the track descriptions are four levels below {@code moov}). */
    private static final int MAX_DEPTH = 8;

    /** The fewest bytes of one entry of a sample description or time-to-sample table. */
    private static final int ENTRY = 8;

    private static final Set<String> TOP_LEVEL = Set.of("ftyp", "moov");

    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl");

    private static final Set<String> LEAVES = Set.of("mvhd", "tkhd", "mdhd", "hdlr", "vmhd", "smhd", "stsd", "stts");

    private final FileChannel channel;
    private final ByteBuffer header = ByteBuffer.allocate(16);
    private int boxes;
    private long copied;

    private IsoBoxes(FileChannel channel) {
        this.channel = channel;
    }

    /** A copied box: a leaf's contents are {@code length} bytes of the file from {@code offset}. */
    private record Box(String type, long offset, int length, List<Box> children) {

        long size() {
            long size = 8;
            if (children == null) {
                return size + length;
            }
            for (Box child : children) {
                size += child.size();
            }
            return size;
        }
    }

    /**
     * The checked copy of {@code file}'s movie box (with its file type box before it); empty when the file has no
     * movie box that could be copied, or has more boxes than are looked at.
     */
    static Optional<byte[]> movie(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            IsoBoxes walk = new IsoBoxes(channel);
            List<Box> top = walk.children(0, channel.size(), 0);
            if (top == null || top.stream().noneMatch(box -> box.type().equals("moov"))) {
                return Optional.empty();
            }
            long size = 0;
            for (Box box : top) {
                size += box.size();
            }
            ByteBuffer out = ByteBuffer.allocate(Math.toIntExact(size));
            for (Box box : top) {
                walk.write(box, out);
            }
            return Optional.of(out.array());
        }
    }

    /**
     * The boxes to copy among those from {@code start} to {@code end}; null when there are more boxes than are looked
     * at.
     */
    private List<Box> children(long start, long end, int depth) throws IOException {
        List<Box> kept = new ArrayList<>();
        long position = start;
        while (end - position >= 8) {
            if (++boxes > MAX_BOXES) {
                return null;
            }
            read(position, 8);
            long size = Integer.toUnsignedLong(header.getInt(0));
            String type = new String(header.array(), 4, 4, StandardCharsets.ISO_8859_1);
            int headerSize = 8;
            if (size == 1) {
                if (end - position < 16) {
                    break;
                }
                read(position + 8, 8);
                size = header.getLong(0);
                headerSize = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < headerSize || size > end - position) {
                break; // the box does not fit in its parent: nothing from here on is trusted
            }
            long contents = position + headerSize;
            long length = size - headerSize;
            boolean wanted = depth == 0 ? TOP_LEVEL.contains(type) : CONTAINERS.contains(type) || LEAVES.contains(type);
            if (wanted && CONTAINERS.contains(type)) {
                if (depth < MAX_DEPTH) {
                    List<Box> inside = children(contents, contents + length, depth + 1);
                    if (inside == null) {
                        return null;
                    }
                    kept.add(new Box(type, contents, 0, inside));
                }
            } else if (wanted && copied + length <= LIMIT && entriesFit(type, contents, length)) {
                copied += length;
                kept.add(new Box(type, contents, (int) length, null));
            }
            position += size;
        }
        return kept;
    }

    /**
     * Whether a table's entry count fits its box: the readers size their lists by the count before reading an entry.
     * A sample description table ({@code stsd}) and a time-to-sample table ({@code stts}) hold a version and flags,
     * the count, then entries of at least {@value #ENTRY} bytes each (a sample description is a box of its own). Other
     * leaves have no count.
     */
    private boolean entriesFit(String type, long contents, long length) throws IOException {
        if (!type.equals("stsd") && !type.equals("stts")) {
            return true;
        }
        if (length < 8) {
            return false;
        }
        read(contents + 4, 4);
        long count = Integer.toUnsignedLong(header.getInt(0));
        return 8 + count * ENTRY <= length;
    }

    private void write(Box box, ByteBuffer out) throws IOException {
        out.putInt(Math.toIntExact(box.size()));
        out.put(box.type().getBytes(StandardCharsets.ISO_8859_1));
        if (box.children() != null) {
            for (Box child : box.children()) {
                write(child, out);
            }
            return;
        }
        ByteBuffer contents = out.slice(out.position(), box.length());
        readFully(contents, box.offset());
        out.position(out.position() + box.length());
    }

    /** Reads {@code count} bytes from {@code position} into the start of the header buffer. */
    private void read(long position, int count) throws IOException {
        header.clear().limit(count);
        readFully(header, position);
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw new EOFException("the file ended inside a box");
            }
            at += read;
        }
    }
}
