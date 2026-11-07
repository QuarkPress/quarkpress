package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
        Path dir = Files.createDirectories(Path.of("target", "av-metadata"));
        Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        return AvMetadata.read(file, mimeType);
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
}
