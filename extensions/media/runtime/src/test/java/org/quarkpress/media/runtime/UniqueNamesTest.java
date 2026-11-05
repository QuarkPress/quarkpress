package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.media.runtime.DefaultMimeTypes.PATTERNS;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UniqueNamesTest {

    private static final Map<String, String> HEIC = MediaConfig.DEFAULT_OUTPUT_FORMATS;

    private final AllowedTypes admin = AllowedTypes.of(PATTERNS, true, List.of(), Optional.empty());

    /** An in-memory folder holding exactly {@code names}, counting its existence checks. */
    private static final class Folder implements UploadFolder {

        private final List<String> names;
        private final AtomicInteger existsCalls = new AtomicInteger();

        Folder(List<String> names) {
            this.names = List.copyOf(names);
        }

        @Override
        public boolean exists(String name) {
            existsCalls.incrementAndGet();
            return names.contains(name);
        }

        @Override
        public List<String> entries() {
            return names;
        }
    }

    private static Folder folder(String... names) {
        return new Folder(List.of(names));
    }

    private String choose(String name, String... existing) {
        return UniqueNames.choose(name, folder(existing), admin, HEIC);
    }

    @Test
    void lowerCaseExtensionTwins() {
        assertThat(choose("IMG_1234.JPG")).isEqualTo("IMG_1234.jpg");
        assertThat(choose("IMG_1234.JPG", "IMG_1234.jpg")).isEqualTo("IMG_1234-1.jpg");
        assertThat(choose("IMG_1234.JPG", "IMG_1234.JPG")).isEqualTo("IMG_1234-1.jpg");
        assertThat(choose("photo.jpg", "Photo.jpg")).isEqualTo("photo.jpg");
    }

    @Test
    void subSizeLikeNamesAreNumberedEvenInAnEmptyFolder() {
        assertThat(choose("logo-150x150.png")).isEqualTo("logo-150x150-1.png");
        assertThat(choose("logo-150x150.png", "logo-150x150-1.png")).isEqualTo("logo-150x150-2.png");
        assertThat(choose("holiday-scaled.jpg")).isEqualTo("holiday-scaled-1.jpg");
        assertThat(choose("holiday-rotated.JPG")).isEqualTo("holiday-rotated-1.jpg");
        assertThat(choose("logo-150X150.png")).isEqualTo("logo-150X150.png");
    }

    @Test
    void existingSubSizesOfTheNameAreAvoidedIgnoringCase() {
        assertThat(choose("photo.jpg", "photo.jpg", "photo-300x200.jpg")).isEqualTo("photo-1.jpg");
        assertThat(choose("photo.jpg", "photo-300x200.jpg")).isEqualTo("photo-1.jpg");
        assertThat(choose("photo.jpg", "Photo-SCALED.JPG")).isEqualTo("photo-1.jpg");
        assertThat(choose("Photo.jpg", "photo-300x200.jpg")).isEqualTo("Photo-1.jpg");
    }

    @Test
    void anExistingFileIsNeverChosen() {
        // WordPress answers photo-1.png here and writes over the existing file; QuarkPress takes the next number.
        assertThat(choose("photo.png", "photo-150x150.png", "photo-1.png")).isEqualTo("photo-2.png");
    }

    @Test
    void alternateFormatTwins() {
        assertThat(choose("trip.jpg", "trip.heic")).isEqualTo("trip-1.jpg");
        assertThat(choose("trip.heic", "trip.jpg")).isEqualTo("trip-1.heic");
        assertThat(choose("trip.heic", "trip-1024x768.jpg")).isEqualTo("trip-1.heic");
        assertThat(choose("trip.heic", "trip.heif")).isEqualTo("trip-1.heic");
        assertThat(UniqueNames.choose("trip.heic", folder("trip.jpg"), admin, Map.of()))
                .isEqualTo("trip.heic");
        assertThat(choose("pic.png", "pic.heic")).isEqualTo("pic.png");
        assertThat(choose("trip.jpg", "trip.heif")).isEqualTo("trip-1.jpg");
        assertThat(UniqueNames.choose("trip.jpg", folder("trip.heif"), admin, Map.of()))
                .isEqualTo("trip.jpg");
        assertThat(choose("photo.jpg", "photo.jpg", "photo-1.heic")).isEqualTo("photo-2.jpg");
        assertThat(UniqueNames.choose("photo.jpg", folder("photo.jpg", "photo-1.heic"), admin, Map.of()))
                .isEqualTo("photo-1.jpg");
    }

    @Test
    void aNameWithoutExtensionGetsDashOne() {
        assertThat(choose("README", "README")).isEqualTo("README-1");
        assertThat(choose("file1", "file1")).isEqualTo("file1-1");
    }

    @Test
    void numberingChangesOnlyTheNamesEnd() {
        assertThat(choose("a.jpg.jpg", "a.jpg")).isEqualTo("a.jpg.jpg");
        assertThat(choose("a.jpg.jpg", "a.jpg.jpg")).isEqualTo("a.jpg-1.jpg");
    }

    @Test
    void onlyAsciiLettersAreLowerCased() {
        // KELVIN SIGN folds to k under Unicode rules; lower-casing here changes A to Z only.
        String kelvin = "x.\u212A";
        assertThat(choose(kelvin)).isEqualTo(kelvin);
        String kelvinBase = "\u212Aey";
        assertThat(choose(kelvinBase + ".jpg", "key-300x200.jpg")).isEqualTo(kelvinBase + ".jpg");
    }

    @Test
    void aFullFolderStillEnds() {
        List<String> names = new ArrayList<>();
        names.add("photo.jpg");
        names.add("photo-300x200.jpg");
        for (int n = 1; n < 500; n++) {
            names.add("photo-" + n + ".jpg");
            names.add("photo-" + n + "-300x200.jpg");
        }
        Folder full = new Folder(names);

        assertThat(UniqueNames.choose("photo.jpg", full, admin, HEIC)).isEqualTo("photo-500.jpg");
        assertThat(full.existsCalls.get()).isLessThan(4 * (names.size() + 2));
    }

    @Test
    void aFolderWhoseExistenceChecksDisagreeWithItsListingStillEnds() {
        UploadFolder everything = new UploadFolder() {
            @Override
            public boolean exists(String name) {
                return true;
            }

            @Override
            public List<String> entries() {
                return List.of("photo.png");
            }
        };

        assertThat(UniqueNames.choose("photo.png", everything, admin, HEIC)).isEqualTo("photo-6.png");
    }
}
