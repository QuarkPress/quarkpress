package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.WriteMode;
import org.quarkpress.posts.PostSnapshot;

/** The URLs of an attachment's files: each one the storage's URL of that file's key. */
class SnapshotUrlsTest {

    private static final SiteInfo MAIN =
            new SiteInfo(SiteId.of(1), "localhost", "/", "Site", 1, false, false, false, false);

    private static final SiteInfo SUB =
            new SiteInfo(SiteId.of(2), "localhost", "/s/", "Sub", 1, false, false, false, false);

    /** A storage that publishes nothing but URLs, built by {@code url} from the site and the key. */
    private record UrlStorage(BiFunction<SiteInfo, String, String> url) implements MediaStorage {

        @Override
        public void put(String key, Path source, String mimeType, WriteMode mode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String folderKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OptionalLong size(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String publicUrl(SiteInfo site, String key) {
            return url.apply(site, StoredKey.require(key));
        }
    }

    /** Signed URLs, as presigned S3 URLs are: the query string belongs to one key and cannot be moved to another. */
    private static final MediaStorage SIGNED =
            new UrlStorage((site, key) -> "https://bucket.s3.test/p/" + key + "?X-Amz-Signature=sig-" + key.hashCode());

    /** URLs under the site's uploads folder, as the filesystem storage gives them. */
    private static final MediaStorage UPLOADS =
            new UrlStorage((site, key) -> site.url("http") + "wp-content/uploads/" + key);

    private static PostSnapshot post(SiteId site) {
        Instant at = Instant.parse("2026-11-11T10:00:00Z");
        return new PostSnapshot(
                PostId.of(7),
                site,
                "attachment",
                "inherit",
                "a",
                "a",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(at),
                at,
                at,
                "open",
                "closed",
                "",
                false,
                "http://localhost/a",
                Map.of(),
                0);
    }

    private static MediaEntity row(SiteId site, String storageKey) {
        MediaEntity row = MediaEntity.create(site, PostId.of(7));
        row.storageKey = storageKey;
        row.mimeType = "image/jpeg";
        row.fileSize = 100;
        row.width = 1600;
        row.height = 1200;
        ObjectNode sizes = JsonNodeFactory.instance.objectNode();
        sizes.putObject("thumbnail")
                .put("file", "a b-150x150.jpg")
                .put("width", 150)
                .put("height", 150)
                .put("mime-type", "image/jpeg")
                .put("filesize", 10);
        sizes.putObject("medium")
                .put("file", "a b-300x225.jpg")
                .put("width", 300)
                .put("height", 225)
                .put("mime-type", "image/jpeg")
                .put("filesize", 20);
        row.sizes = sizes;
        row.altText = "";
        row.processing = MediaEntity.DONE;
        return row;
    }

    @Test
    void eachSizeUrlIsTheStoragesUrlOfItsKey() {
        MediaSnapshot media =
                JpaMedia.snapshot(post(SiteId.of(1)), row(SiteId.of(1), "2026/11/a b.jpg"), SIGNED, MAIN, 1);
        assertThat(media.url()).isEqualTo(SIGNED.publicUrl(MAIN, "2026/11/a b.jpg"));
        assertThat(media.url(MediaSnapshot.FULL)).isEqualTo(media.url());
        assertThat(media.url("thumbnail")).isEqualTo(SIGNED.publicUrl(MAIN, "2026/11/a b-150x150.jpg"));
        assertThat(media.url("medium")).isEqualTo(SIGNED.publicUrl(MAIN, "2026/11/a b-300x225.jpg"));
        assertThat(media.sizeUrls()).containsOnlyKeys("thumbnail", "medium");
        assertThatThrownBy(() -> media.url("large")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSubdirectorySitesSizesKeepItsFolder() {
        MediaSnapshot media =
                JpaMedia.snapshot(post(SiteId.of(2)), row(SiteId.of(2), "sites/2/2026/11/a b.jpg"), SIGNED, SUB, 1);
        assertThat(media.url("thumbnail")).isEqualTo(SIGNED.publicUrl(SUB, "sites/2/2026/11/a b-150x150.jpg"));
    }

    @Test
    void uploadsFolderUrlsAreTheMainFilesFolderAndTheSizesName() {
        MediaSnapshot media =
                JpaMedia.snapshot(post(SiteId.of(1)), row(SiteId.of(1), "2026/11/a b.jpg"), UPLOADS, MAIN, 1);
        assertThat(media.url()).isEqualTo("http://localhost/wp-content/uploads/2026/11/a b.jpg");
        assertThat(media.url("thumbnail")).isEqualTo("http://localhost/wp-content/uploads/2026/11/a b-150x150.jpg");
        assertThat(media.url("medium")).isEqualTo("http://localhost/wp-content/uploads/2026/11/a b-300x225.jpg");
    }

    @Test
    void aSnapshotNeedsAUrlForEverySize() {
        MediaSnapshot media =
                JpaMedia.snapshot(post(SiteId.of(1)), row(SiteId.of(1), "2026/11/a b.jpg"), SIGNED, MAIN, 1);
        assertThatThrownBy(() -> new MediaSnapshot(
                        media.post(),
                        media.mimeType(),
                        media.storageKey(),
                        media.attachedFile(),
                        media.originalImage(),
                        media.fileSize(),
                        media.width(),
                        media.height(),
                        media.sizes(),
                        media.imageMeta(),
                        media.extra(),
                        media.altText(),
                        media.processing(),
                        media.url(),
                        Map.of("thumbnail", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("medium");
    }
}
