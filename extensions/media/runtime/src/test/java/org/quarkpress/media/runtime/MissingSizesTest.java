package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.posts.PostSnapshot;

/** Which registered sizes an attachment lacks, as WordPress lists its missing image sizes. */
class MissingSizesTest {

    static final Map<String, ImageSizeDefinition> DEFAULTS =
            BuiltInImageSizes.resolve(BuiltInImageSizes.registrations(List.of()), name -> Optional.empty());

    static final List<String> SIX = List.of("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");

    private static PostSnapshot post() {
        Instant at = Instant.parse("2026-11-05T10:00:00Z");
        return new PostSnapshot(
                PostId.of(7),
                SiteId.of(1),
                "attachment",
                "inherit",
                "x",
                "x",
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
                "http://localhost/x",
                Map.of(),
                0);
    }

    private static MediaSnapshot media(
            String mimeType,
            Optional<String> originalImage,
            OptionalInt width,
            OptionalInt height,
            List<String> sizeNames,
            Processing processing) {
        Map<String, MediaSize> sizes = new LinkedHashMap<>();
        Map<String, String> urls = new LinkedHashMap<>();
        for (String name : sizeNames) {
            sizes.put(name, new MediaSize("x-" + name + ".jpg", 1, 1, "image/jpeg", 1));
            urls.put(name, "http://localhost/x-" + name + ".jpg");
        }
        ObjectNode meta = JsonNodeFactory.instance.objectNode().put("orientation", "1");
        return new MediaSnapshot(
                post(),
                mimeType,
                "2026/11/x.jpg",
                "2026/11/x.jpg",
                originalImage,
                100,
                width,
                height,
                sizes,
                mimeType.startsWith("image/") && width.isPresent() ? Optional.of(meta) : Optional.empty(),
                JsonNodeFactory.instance.objectNode(),
                "",
                processing,
                "http://localhost/x.jpg",
                urls);
    }

    private static MediaSnapshot image(int width, int height, List<String> sizes) {
        return media(
                "image/jpeg", Optional.empty(), OptionalInt.of(width), OptionalInt.of(height), sizes, Processing.DONE);
    }

    private static Optional<ImageInfo> original(int width, int height) {
        return Optional.of(new ImageInfo("image/jpeg", width, height));
    }

    @Test
    void missingSizes() {
        MediaSnapshot complete4000x3000 = media(
                "image/jpeg",
                Optional.of("x.jpg"),
                OptionalInt.of(2560),
                OptionalInt.of(1920),
                List.of("medium", "large", "thumbnail", "medium_large", "1536x1536", "2048x2048"),
                Processing.DONE);
        MediaSnapshot base4000x3000 = media(
                "image/jpeg",
                Optional.of("x.jpg"),
                OptionalInt.of(2560),
                OptionalInt.of(1920),
                List.of(),
                Processing.PARTIAL);
        MediaSnapshot base1200x800 = image(1200, 800, List.of());
        MediaSnapshot heicFileSizeOnly = media(
                "image/heic", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), List.of(), Processing.DONE);
        MediaSnapshot pendingImage = media(
                "image/jpeg", Optional.empty(), OptionalInt.of(64), OptionalInt.of(48), List.of(), Processing.PENDING);
        MediaSnapshot pdf = media(
                "application/pdf",
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                List.of(),
                Processing.DONE);

        assertThat(MissingSizes.of(complete4000x3000, DEFAULTS, original(4000, 3000)))
                .isEmpty();
        // registered order, not generation order; the original's dimensions, not the scaled main file's
        assertThat(MissingSizes.of(base4000x3000, DEFAULTS, original(4000, 3000)))
                .containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
        assertThat(MissingSizes.of(base1200x800, DEFAULTS, Optional.empty()))
                .containsExactly("thumbnail", "medium", "medium_large", "large");
        assertThat(MissingSizes.of(heicFileSizeOnly, DEFAULTS, Optional.empty()))
                .isEmpty();
        // no metadata yet: every registered size, whatever the dimensions
        assertThat(MissingSizes.of(pendingImage, DEFAULTS, Optional.empty())).hasSize(6);
        assertThat(MissingSizes.of(pendingImage, DEFAULTS, Optional.empty())).containsExactlyElementsOf(SIX);
        assertThat(MissingSizes.of(pdf, DEFAULTS, Optional.empty())).isEmpty();
    }

    @Test
    void aFailedAttachmentHasNoMetadataAndLacksEverySize() {
        // no run saved its metadata: every size, whatever the stored dimensions allow
        for (OptionalInt width : List.of(OptionalInt.empty(), OptionalInt.of(200))) {
            OptionalInt height = width.isPresent() ? OptionalInt.of(100) : OptionalInt.empty();
            MediaSnapshot failed =
                    media("image/jpeg", Optional.empty(), width, height, List.of("thumbnail"), Processing.FAILED);
            assertThat(MissingSizes.hasNoMetadata(failed)).isTrue();
            assertThat(MissingSizes.of(failed, DEFAULTS, Optional.empty())).containsExactlyElementsOf(SIX);
        }
        // a partial run saved its metadata: what fits and is not there
        MediaSnapshot partial = media(
                "image/jpeg",
                Optional.empty(),
                OptionalInt.of(200),
                OptionalInt.of(100),
                List.of(),
                Processing.PARTIAL);
        assertThat(MissingSizes.hasNoMetadata(partial)).isFalse();
        assertThat(MissingSizes.of(partial, DEFAULTS, Optional.empty())).containsExactly("thumbnail");
        // a finished run's file size alone is metadata, as in WordPress: nothing is missing
        MediaSnapshot fileSizeOnly = media(
                "image/jpeg", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), List.of(), Processing.DONE);
        assertThat(MissingSizes.hasNoMetadata(fileSizeOnly)).isFalse();
        assertThat(MissingSizes.of(fileSizeOnly, DEFAULTS, Optional.empty())).isEmpty();
        // a failed non-image has no metadata either, but never lacks a size
        MediaSnapshot failedVideo = media(
                "video/mp4", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), List.of(), Processing.FAILED);
        assertThat(MissingSizes.hasNoMetadata(failedVideo)).isTrue();
        assertThat(MissingSizes.of(failedVideo, DEFAULTS, Optional.empty())).isEmpty();
    }

    @Test
    void theOriginalIsUsedOnlyWhenTheMainFileIsACopy() {
        // the original's dimensions are read only when there is an original image
        MediaSnapshot small = image(1200, 800, List.of());
        assertThat(MissingSizes.of(small, DEFAULTS, original(4000, 3000)))
                .containsExactly("thumbnail", "medium", "medium_large", "large");
        // when the original cannot be read, the main file's dimensions count
        MediaSnapshot scaled = media(
                "image/jpeg",
                Optional.of("x.jpg"),
                OptionalInt.of(2560),
                OptionalInt.of(1920),
                List.of(),
                Processing.PARTIAL);
        assertThat(MissingSizes.of(scaled, DEFAULTS, Optional.empty()))
                .containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
        MediaSnapshot rotated = media(
                "image/jpeg",
                Optional.of("x.jpg"),
                OptionalInt.of(200),
                OptionalInt.of(160),
                List.of(),
                Processing.PARTIAL);
        assertThat(MissingSizes.of(rotated, DEFAULTS, Optional.empty())).containsExactly("thumbnail");
        assertThat(MissingSizes.of(rotated, DEFAULTS, original(1200, 800)))
                .containsExactly("thumbnail", "medium", "medium_large", "large");
    }

    @Test
    void presentNamesAndImpossibleSizesAreNotMissing() {
        assertThat(MissingSizes.of(image(1200, 800, List.of("medium", "thumbnail")), DEFAULTS, Optional.empty()))
                .containsExactly("medium_large", "large");
        // a size of the same name counts as present whatever its file
        assertThat(MissingSizes.of(
                        image(1200, 800, List.of("thumbnail", "medium", "medium_large", "large")),
                        DEFAULTS,
                        Optional.empty()))
                .isEmpty();
        // 120x90: smaller than every box, and the cropped thumbnail would be the image itself: no size is possible
        assertThat(MissingSizes.of(image(120, 90, List.of()), DEFAULTS, Optional.empty()))
                .isEmpty();
        // 200x100: only the cropped thumbnail (150x100) is possible
        assertThat(MissingSizes.of(image(200, 100, List.of()), DEFAULTS, Optional.empty()))
                .containsExactly("thumbnail");
        // an image type whose dimensions are unknown: nothing is possible
        assertThat(MissingSizes.of(
                        media(
                                "image/tiff",
                                Optional.empty(),
                                OptionalInt.empty(),
                                OptionalInt.empty(),
                                List.of(),
                                Processing.DONE),
                        DEFAULTS,
                        Optional.empty()))
                .isEmpty();
        // a non-image is never missing anything, even pending
        assertThat(MissingSizes.of(
                        media(
                                "video/mp4",
                                Optional.empty(),
                                OptionalInt.of(1280),
                                OptionalInt.of(720),
                                List.of(),
                                Processing.PENDING),
                        DEFAULTS,
                        Optional.empty()))
                .isEmpty();
    }

    @Test
    void sizesAreCheckedOnTheStoredUnrotatedDimensions() {
        // WordPress's quirk: 1000x700 with orientation 6 and an app size 900 wide is listed (1000 >= 900), though the
        // upright image (700 wide) can never give it
        Map<String, ImageSizeDefinition> withApp = new LinkedHashMap<>(DEFAULTS);
        withApp.put("app", new ImageSizeDefinition("app", 900, 0, Crop.none()));
        MediaSnapshot oriented = media(
                "image/jpeg",
                Optional.empty(),
                OptionalInt.of(1000),
                OptionalInt.of(700),
                List.of("medium", "thumbnail", "medium_large"),
                Processing.DONE);
        assertThat(MissingSizes.of(oriented, withApp, Optional.empty())).containsExactly("app");
    }
}
