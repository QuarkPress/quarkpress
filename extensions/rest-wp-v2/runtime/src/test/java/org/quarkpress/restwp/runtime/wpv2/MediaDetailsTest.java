package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.PhpObjectNode;

/** An attachment's {@code media_details}: the stored metadata in WordPress's REST shape. */
class MediaDetailsTest {

    private static final String UPLOADS = "http://wp.test/wp-content/uploads/2026/11/";

    private static PostSnapshot post() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        return new PostSnapshot(
                PostId.of(7),
                SiteId.of(1),
                "attachment",
                "inherit",
                "a",
                "a",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(now),
                now,
                now,
                "open",
                "closed",
                "",
                false,
                UPLOADS + "a.jpg",
                Map.of(),
                1);
    }

    private static ObjectNode imageMeta() {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("aperture", "0").put("orientation", "1");
        meta.putArray("keywords");
        return meta;
    }

    private static MediaSnapshot media(
            String mimeType,
            String attachedFile,
            Optional<String> originalImage,
            long fileSize,
            OptionalInt width,
            OptionalInt height,
            Map<String, MediaSize> sizes,
            Optional<ObjectNode> imageMeta,
            ObjectNode extra,
            Processing processing) {
        return at(
                attachedFile.isEmpty() ? "" : "http://wp.test/wp-content/uploads/" + attachedFile,
                mimeType,
                attachedFile,
                originalImage,
                fileSize,
                width,
                height,
                sizes,
                imageMeta,
                extra,
                processing);
    }

    /** The attachment with its main file at {@code url} and each size's file in that URL's folder. */
    private static MediaSnapshot at(
            String url,
            String mimeType,
            String attachedFile,
            Optional<String> originalImage,
            long fileSize,
            OptionalInt width,
            OptionalInt height,
            Map<String, MediaSize> sizes,
            Optional<ObjectNode> imageMeta,
            ObjectNode extra,
            Processing processing) {
        Map<String, String> sizeUrls = new LinkedHashMap<>();
        sizes.forEach((name, size) -> sizeUrls.put(name, url.substring(0, url.lastIndexOf('/') + 1) + size.file()));
        return withUrls(
                url,
                sizeUrls,
                mimeType,
                attachedFile,
                originalImage,
                fileSize,
                width,
                height,
                sizes,
                imageMeta,
                extra,
                processing);
    }

    private static MediaSnapshot withUrls(
            String url,
            Map<String, String> sizeUrls,
            String mimeType,
            String attachedFile,
            Optional<String> originalImage,
            long fileSize,
            OptionalInt width,
            OptionalInt height,
            Map<String, MediaSize> sizes,
            Optional<ObjectNode> imageMeta,
            ObjectNode extra,
            Processing processing) {
        return new MediaSnapshot(
                post(),
                mimeType,
                attachedFile.isEmpty() ? "" : "sites/1/" + attachedFile,
                attachedFile,
                originalImage,
                fileSize,
                width,
                height,
                sizes,
                imageMeta,
                extra,
                "",
                processing,
                url,
                sizeUrls);
    }

    private static MediaSnapshot jpeg(Processing processing, Map<String, MediaSize> sizes) {
        return media(
                "image/jpeg",
                "2026/11/a.jpg",
                Optional.empty(),
                50000,
                OptionalInt.of(4000),
                OptionalInt.of(3000),
                sizes,
                Optional.of(imageMeta()),
                JsonNodeFactory.instance.objectNode(),
                processing);
    }

    private static Map<String, MediaSize> medium(String file) {
        Map<String, MediaSize> sizes = new LinkedHashMap<>();
        sizes.put("medium", new MediaSize(file, 300, 225, "image/jpeg", 9000));
        return sizes;
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void sizesGetTheirMimeTypeLastAndAUrl() {
        Map<String, MediaSize> sizes = medium("a-300x225.jpg");
        sizes.put("thumbnail", new MediaSize("a-150x150.jpg", 150, 150, "image/jpeg", 4000));
        ObjectNode details = MediaDetails.of(jpeg(Processing.DONE, sizes), UPLOADS + "a.jpg", false);
        assertThat(keys(details)).containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
        assertThat(keys(details.get("sizes"))).containsExactly("medium", "thumbnail", "full");
        assertThat(details.at("/sizes/medium").toString())
                .isEqualTo("{\"file\":\"a-300x225.jpg\",\"width\":300,\"height\":225,\"filesize\":9000,"
                        + "\"mime_type\":\"image/jpeg\",\"source_url\":\"" + UPLOADS + "a-300x225.jpg\"}");
        assertThat(details.at("/sizes/full").toString())
                .isEqualTo("{\"file\":\"a.jpg\",\"width\":4000,\"height\":3000,\"mime_type\":\"image/jpeg\","
                        + "\"source_url\":\"" + UPLOADS + "a.jpg\"}");
        assertThat(details.at("/image_meta").toString())
                .isEqualTo("{\"aperture\":\"0\",\"orientation\":\"1\",\"keywords\":[]}");
        // the size URLs take a secure request's scheme, as the main URL does
        assertThat(MediaDetails.of(
                                jpeg(Processing.DONE, medium("a-300x225.jpg")),
                                "https://wp.test/wp-content/uploads/2026/11/a.jpg",
                                true)
                        .at("/sizes/medium/source_url")
                        .asText())
                .isEqualTo("https://wp.test/wp-content/uploads/2026/11/a-300x225.jpg");
        // for files under one folder, every occurrence of the main file's name is replaced, as WordPress's plain text
        // replacement does
        String odd = "http://wp.test/a.jpg/2026/a.jpg";
        MediaSnapshot oddFolder = at(
                odd,
                "image/jpeg",
                "2026/a.jpg",
                Optional.empty(),
                50000,
                OptionalInt.of(4000),
                OptionalInt.of(3000),
                medium("a-300x225.jpg"),
                Optional.of(imageMeta()),
                JsonNodeFactory.instance.objectNode(),
                Processing.DONE);
        assertThat(MediaDetails.of(oddFolder, odd, false)
                        .at("/sizes/medium/source_url")
                        .asText())
                .isEqualTo("http://wp.test/a-300x225.jpg/2026/a-300x225.jpg");
        // the attachment's type, not the size's, describes the full size
        MediaSnapshot heicWithSizes = media(
                "image/heic",
                "2026/11/h.heic",
                Optional.empty(),
                800,
                OptionalInt.of(40),
                OptionalInt.of(30),
                Map.of("medium", new MediaSize("h-300x225.jpg", 30, 22, "image/jpeg", 100)),
                Optional.empty(),
                JsonNodeFactory.instance.objectNode(),
                Processing.DONE);
        ObjectNode heic = MediaDetails.of(heicWithSizes, UPLOADS + "h.heic", false);
        assertThat(heic.at("/sizes/medium/mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(heic.at("/sizes/full/mime_type").asText()).isEqualTo("image/heic");
    }

    @Test
    void emptyAndAbsentSizesBecomeObjects() {
        MediaSnapshot heic = media(
                "image/heic",
                "2026/11/h.heic",
                Optional.empty(),
                231,
                OptionalInt.empty(),
                OptionalInt.empty(),
                Map.of(),
                Optional.empty(),
                JsonNodeFactory.instance.objectNode(),
                Processing.NONE);
        ObjectNode details = MediaDetails.of(heic, UPLOADS + "h.heic", false);
        assertThat(details.toString()).isEqualTo("{\"filesize\":231,\"sizes\":{}}");
        assertThat(details.get("sizes")).isInstanceOf(PhpObjectNode.class);
        ObjectNode extra = JsonNodeFactory.instance.objectNode();
        extra.put("dataformat", "mp3").put("filesize", 1234).put("length", 1);
        extra.putObject("image").put("mime", "image/jpeg");
        MediaSnapshot mp3 = media(
                "audio/mpeg",
                "2026/11/s.mp3",
                Optional.empty(),
                1234,
                OptionalInt.empty(),
                OptionalInt.empty(),
                Map.of(),
                Optional.empty(),
                extra,
                Processing.NONE);
        assertThat(MediaDetails.of(mp3, UPLOADS + "s.mp3", false).toString())
                .isEqualTo("{\"dataformat\":\"mp3\",\"filesize\":1234,\"length\":1,\"image\":{\"mime\":\"image/jpeg\"},"
                        + "\"sizes\":{}}");
        // an image without sizes keeps its empty sizes in place and gets no full size
        ObjectNode unsized = MediaDetails.of(jpeg(Processing.DONE, Map.of()), UPLOADS + "a.jpg", false);
        assertThat(keys(unsized)).containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
        assertThat(unsized.get("sizes").toString()).isEqualTo("{}");
        assertThat(unsized.get("sizes")).isInstanceOf(PhpObjectNode.class);
        // no metadata: still being made, failed (whatever its header gave), or no stored file
        for (Processing nothingYet : List.of(Processing.PENDING, Processing.FAILED)) {
            ObjectNode none = MediaDetails.of(jpeg(nothingYet, Map.of()), UPLOADS + "a.jpg", false);
            assertThat(none.toString()).as(nothingYet.name()).isEqualTo("{}");
            assertThat(none).isInstanceOf(PhpObjectNode.class);
        }
        MediaSnapshot noFile = media(
                "",
                "",
                Optional.empty(),
                0,
                OptionalInt.empty(),
                OptionalInt.empty(),
                Map.of(),
                Optional.empty(),
                JsonNodeFactory.instance.objectNode(),
                Processing.NONE);
        assertThat(MediaDetails.of(noFile, "", false).toString()).isEqualTo("{}");
    }

    @Test
    void scaledImagesDescribeTheScaledFile() {
        MediaSnapshot scaled = media(
                "image/jpeg",
                "2026/11/a-scaled.jpg",
                Optional.of("a.jpg"),
                100515,
                OptionalInt.of(2560),
                OptionalInt.of(1920),
                medium("a-300x225.jpg"),
                Optional.of(imageMeta()),
                JsonNodeFactory.instance.objectNode(),
                Processing.DONE);
        ObjectNode details = MediaDetails.of(scaled, UPLOADS + "a-scaled.jpg", false);
        assertThat(keys(details))
                .containsExactly("width", "height", "file", "filesize", "sizes", "image_meta", "original_image");
        assertThat(details.get("file").asText()).isEqualTo("2026/11/a-scaled.jpg");
        assertThat(details.get("original_image").asText()).isEqualTo("a.jpg");
        assertThat(details.at("/sizes/medium/source_url").asText()).isEqualTo(UPLOADS + "a-300x225.jpg");
        assertThat(details.at("/sizes/full").toString())
                .isEqualTo("{\"file\":\"a-scaled.jpg\",\"width\":2560,\"height\":1920,\"mime_type\":\"image/jpeg\","
                        + "\"source_url\":\"" + UPLOADS + "a-scaled.jpg\"}");
    }

    @Test
    void signedSizeUrlsAreTheStoragesOwn() {
        // a storage whose URLs carry a signature for one file: each size keeps the URL the storage gave it
        String main = "https://bucket.s3.test/p/2026/11/a.jpg?X-Amz-Signature=main";
        Map<String, MediaSize> sizes = medium("a-300x225.jpg");
        sizes.put("thumbnail", new MediaSize("a-150x150.jpg", 150, 150, "image/jpeg", 4000));
        MediaSnapshot signed = withUrls(
                main,
                Map.of(
                        "medium", "https://bucket.s3.test/p/2026/11/a-300x225.jpg?X-Amz-Signature=medium",
                        "thumbnail", "https://bucket.s3.test/p/2026/11/a-150x150.jpg?X-Amz-Signature=thumbnail"),
                "image/jpeg",
                "2026/11/a.jpg",
                Optional.empty(),
                50000,
                OptionalInt.of(4000),
                OptionalInt.of(3000),
                sizes,
                Optional.of(imageMeta()),
                JsonNodeFactory.instance.objectNode(),
                Processing.DONE);
        ObjectNode details = MediaDetails.of(signed, main, true);
        assertThat(details.at("/sizes/medium/source_url").asText())
                .isEqualTo("https://bucket.s3.test/p/2026/11/a-300x225.jpg?X-Amz-Signature=medium");
        assertThat(details.at("/sizes/thumbnail/source_url").asText())
                .isEqualTo("https://bucket.s3.test/p/2026/11/a-150x150.jpg?X-Amz-Signature=thumbnail");
        assertThat(details.at("/sizes/full/source_url").asText()).isEqualTo(main);
        // a plain http storage URL takes a secure request's scheme
        MediaSnapshot plain = withUrls(
                "http://bucket.s3.test/p/2026/11/a.jpg?v=1",
                Map.of("medium", "http://bucket.s3.test/p/2026/11/a-300x225.jpg?v=1"),
                "image/jpeg",
                "2026/11/a.jpg",
                Optional.empty(),
                50000,
                OptionalInt.of(4000),
                OptionalInt.of(3000),
                medium("a-300x225.jpg"),
                Optional.of(imageMeta()),
                JsonNodeFactory.instance.objectNode(),
                Processing.DONE);
        assertThat(MediaDetails.of(plain, "https://bucket.s3.test/p/2026/11/a.jpg?v=1", true)
                        .at("/sizes/medium/source_url")
                        .asText())
                .isEqualTo("https://bucket.s3.test/p/2026/11/a-300x225.jpg?v=1");
    }
}
