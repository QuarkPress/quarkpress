package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/** The audio and video metadata read from the containers metadata-extractor understands. */
class AvMetadataTest {

    private static ObjectNode read(byte[] bytes, String mimeType) throws IOException {
        return AvMetadata.read(write(bytes), mimeType);
    }

    private static Path write(byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(Path.of("target", "av-metadata"));
        return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
    }

    private static List<String> keys(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void videoGetsItsDurationAndDimensions() throws IOException {
        byte[] mp4 = MediaFixtures.mp4(1280, 720, 65);
        ObjectNode metadata = read(mp4, "video/mp4");
        assertThat(keys(metadata))
                .containsExactly("filesize", "mime_type", "length", "length_formatted", "width", "height");
        assertThat(metadata.get("filesize").longValue()).isEqualTo(mp4.length);
        assertThat(metadata.get("mime_type").textValue()).isEqualTo("video/mp4");
        assertThat(metadata.get("length").intValue()).isEqualTo(65);
        assertThat(metadata.get("length").isInt()).isTrue();
        assertThat(metadata.get("length_formatted").textValue()).isEqualTo("1:05");
        assertThat(metadata.get("width").intValue()).isEqualTo(1280);
        assertThat(metadata.get("height").intValue()).isEqualTo(720);
        // the attachment's type is stored, whatever the container says
        assertThat(read(mp4, "video/quicktime").get("mime_type").textValue()).isEqualTo("video/quicktime");
    }

    @Test
    void audioGetsItsDuration() throws IOException {
        ObjectNode m4a = read(MediaFixtures.m4a(), "audio/mpeg");
        assertThat(keys(m4a)).containsExactly("filesize", "mime_type", "length", "length_formatted");
        assertThat(m4a.get("length").intValue()).isEqualTo(1);
        assertThat(m4a.get("length_formatted").textValue()).isEqualTo("0:01");
        ObjectNode wav = read(MediaFixtures.wav(3), "audio/wav");
        assertThat(keys(wav)).containsExactly("filesize", "mime_type", "length", "length_formatted");
        assertThat(wav.get("length").intValue()).isEqualTo(3);
        assertThat(wav.get("length_formatted").textValue()).isEqualTo("0:03");
    }

    @Test
    void otherContainersGetTheirSizeAndTypeOnly() throws IOException {
        assertThat(keys(read(MediaFixtures.mp3(Map.of(), Optional.empty()), "audio/mpeg")))
                .containsExactly("filesize", "mime_type");
        assertThat(keys(read(MediaFixtures.webm(), "video/webm"))).containsExactly("filesize", "mime_type");
        assertThat(keys(read(MediaFixtures.ogg(), "audio/ogg"))).containsExactly("filesize", "mime_type");
        assertThat(keys(read(MediaFixtures.text("not a video\n"), "video/mp4")))
                .containsExactly("filesize", "mime_type");
        // a damaged container: what can be read, never a failure
        byte[] mp4 = MediaFixtures.mp4(64, 48, 2);
        ObjectNode truncated = read(Arrays.copyOf(mp4, 40), "video/mp4");
        assertThat(keys(truncated)).startsWith("filesize", "mime_type");
        assertThat(truncated.get("filesize").longValue()).isEqualTo(40);
    }

    @Test
    void aBoxDeclaringMoreThanTheFileHoldsAllocatesNothing() throws IOException {
        int huge = 0x7FFF0000; // about 2 GB, in files of a few dozen bytes
        byte[] mvhdHeader = concat(u32(huge), ascii("mvhd"), new byte[16]);
        byte[] nestedMp4 = concat(box("ftyp", ascii("isom"), u32(0x200), ascii("isom")), box("moov", mvhdHeader));
        byte[] nestedQuickTime = concat(box("ftyp", ascii("qt  "), u32(0x200), ascii("qt  ")), box("moov", mvhdHeader));
        byte[] topLevel = concat(u32(huge), ascii("ftyp"), ascii("isom"), u32(0x200), ascii("isom"));
        byte[] largeSize = concat(
                box("ftyp", ascii("isom"), u32(0x200), ascii("isom")),
                u32(1),
                ascii("moov"),
                u32(0),
                u32(huge),
                mvhdHeader);
        byte[] wav = ByteBuffer.allocate(40)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii("RIFF"))
                .putInt(huge)
                .put(ascii("WAVE"))
                .put(ascii("fmt "))
                .putInt(huge - 16)
                .array();
        byte[] avi = ByteBuffer.allocate(48)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii("RIFF"))
                .putInt(huge)
                .put(ascii("AVI "))
                .put(ascii("LIST"))
                .putInt(huge - 16)
                .put(ascii("hdrl"))
                .put(ascii("avih"))
                .putInt(huge - 32)
                .array();
        Map<String, byte[]> files = Map.of(
                "nested mp4", nestedMp4,
                "nested quicktime", nestedQuickTime,
                "top-level box", topLevel,
                "64-bit size", largeSize,
                "wav", wav,
                "avi", avi);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            long before = allocatedBytes();
            ObjectNode metadata = read(file.getValue(), "video/mp4");
            long allocated = allocatedBytes() - before;
            assertThat(keys(metadata)).as(file.getKey()).containsExactly("filesize", "mime_type");
            assertThat(metadata.get("filesize").longValue()).as(file.getKey()).isEqualTo(file.getValue().length);
            assertThat(allocated).as(file.getKey() + ": bytes allocated").isLessThan(64L << 20);
        }
    }

    @Test
    void entryCountsBeyondTheirBoxAllocateNothing() throws IOException {
        int count = 300_000_000;
        byte[] hdlr = box("hdlr", u32(0), u32(0), ascii("vide"), u32(0), u32(0), u32(0), new byte[1]);
        Map<String, byte[]> leaves = Map.of(
                "stts", box("stts", u32(0), u32(count), u32(1), u32(1)),
                "stsd", box("stsd", u32(0), u32(count)));
        for (String brand : List.of("qt  ", "isom")) {
            for (Map.Entry<String, byte[]> leaf : leaves.entrySet()) {
                byte[] movie = box("moov", box("trak", box("mdia", hdlr, box("minf", box("stbl", leaf.getValue())))));
                Path file = write(concat(box("ftyp", ascii(brand), u32(0), ascii(brand)), movie));
                long before = allocatedBytes();
                ObjectNode metadata = AvMetadata.read(file, "video/quicktime");
                long allocated = allocatedBytes() - before;
                assertThat(keys(metadata)).as(brand + leaf.getKey()).startsWith("filesize", "mime_type");
                assertThat(allocated)
                        .as(brand + " " + leaf.getKey() + ": bytes allocated")
                        .isLessThan(64L << 20);
            }
        }
    }

    @Test
    void aBoxReachingPastItsParentEndsTheParent() throws IOException {
        // the track box declares 8 bytes more than the movie box holds; they exist in the file (the media data box)
        byte[] mp4 = MediaFixtures.mp4(320, 240, 7);
        int trak = indexOf(mp4, "trak") - 4;
        ByteBuffer.wrap(mp4).putInt(trak, ByteBuffer.wrap(mp4).getInt(trak) + 8);
        ObjectNode metadata = read(mp4, "video/mp4");
        assertThat(keys(metadata)).containsExactly("filesize", "mime_type", "length", "length_formatted");
        assertThat(metadata.get("length").intValue()).isEqualTo(7);
    }

    @Test
    void boxesBeyondTheCopyLimitAreLeftOut() throws IOException {
        // a time-to-sample table of just over the copy limit, whose entries fit it: left out, the rest still read
        byte[] mp4 = MediaFixtures.mp4(320, 240, 7);
        int stbl = indexOf(mp4, "stbl") - 4;
        int entries = (IsoBoxes.LIMIT / 8) + 1;
        byte[] stts = concat(u32(16 + entries * 8), ascii("stts"), u32(0), u32(entries), new byte[entries * 8]);
        ByteBuffer patched = ByteBuffer.allocate(mp4.length + stts.length);
        patched.put(mp4, 0, stbl + 8).put(stts).put(mp4, stbl + 8, mp4.length - stbl - 8);
        byte[] bytes = patched.array();
        for (String type : List.of("moov", "trak", "mdia", "minf", "stbl")) {
            int at = indexOf(bytes, type) - 4;
            ByteBuffer.wrap(bytes).putInt(at, ByteBuffer.wrap(bytes).getInt(at) + stts.length);
        }
        Path file = write(bytes);
        long before = allocatedBytes();
        ObjectNode metadata = AvMetadata.read(file, "video/mp4");
        long allocated = allocatedBytes() - before;
        assertThat(metadata.get("length").intValue()).isEqualTo(7);
        assertThat(metadata.get("width").intValue()).isEqualTo(320);
        assertThat(allocated).as("bytes allocated").isLessThan(16L << 20);
    }

    @Test
    void theMovieBoxIsFoundAfterTheMediaData() throws IOException {
        byte[] mp4 = MediaFixtures.mp4(320, 240, 7);
        int moov = indexOf(mp4, "moov") - 4;
        int mdat = indexOf(mp4, "mdat") - 4;
        byte[] ftyp = Arrays.copyOfRange(mp4, 0, moov);
        byte[] movie = Arrays.copyOfRange(mp4, moov, mdat);
        byte[] media = box("mdat", new byte[1 << 20]);
        ObjectNode metadata = read(concat(ftyp, media, box("free", new byte[3]), movie), "video/mp4");
        assertThat(metadata.get("length").intValue()).isEqualTo(7);
        assertThat(metadata.get("width").intValue()).isEqualTo(320);
        assertThat(metadata.get("height").intValue()).isEqualTo(240);
    }

    @Test
    void durationTextIsReadInWholeSeconds() {
        assertThat(AvMetadata.clock("00:00:03")).isEqualTo(OptionalDouble.of(3));
        assertThat(AvMetadata.clock("00:01:05")).isEqualTo(OptionalDouble.of(65));
        assertThat(AvMetadata.clock("00:59:59")).isEqualTo(OptionalDouble.of(3599));
        // from one hour on metadata-extractor counts the seconds past the minutes from the start
        assertThat(AvMetadata.clock("01:01:3640")).isEqualTo(OptionalDouble.of(3700));
        assertThat(AvMetadata.clock("02:02:7202")).isEqualTo(OptionalDouble.of(7322));
        assertThat(AvMetadata.clock("01:00:3600")).isEqualTo(OptionalDouble.of(3600));
        // a corrected form reads the same
        assertThat(AvMetadata.clock("01:01:40")).isEqualTo(OptionalDouble.of(3700));
        for (String text : new String[] {null, "", "3", "1:2", "1:2:3:4", "a:00:01", "00::01", "00:00:", "-1:00:00"}) {
            assertThat(AvMetadata.clock(text)).as("%s", text).isEmpty();
        }
    }

    @Test
    void lengthsAreFormattedAsWordPressShowsThem() {
        assertThat(AvMetadata.formatted(0)).isEqualTo("0:00");
        assertThat(AvMetadata.formatted(3)).isEqualTo("0:03");
        assertThat(AvMetadata.formatted(65)).isEqualTo("1:05");
        assertThat(AvMetadata.formatted(600)).isEqualTo("10:00");
        assertThat(AvMetadata.formatted(3599)).isEqualTo("59:59");
        assertThat(AvMetadata.formatted(3600)).isEqualTo("1:00:00");
        assertThat(AvMetadata.formatted(3700)).isEqualTo("1:01:40");
        assertThat(AvMetadata.formatted(36125)).isEqualTo("10:02:05");
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private static int indexOf(byte[] bytes, String type) {
        byte[] wanted = ascii(type);
        for (int i = 0; i + wanted.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + wanted.length, wanted, 0, wanted.length)) {
                return i;
            }
        }
        throw new AssertionError(type + " not found");
    }

    private static byte[] box(String type, byte[]... content) {
        byte[] body = concat(content);
        return concat(u32(8 + body.length), ascii(type), body);
    }

    private static byte[] u32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
