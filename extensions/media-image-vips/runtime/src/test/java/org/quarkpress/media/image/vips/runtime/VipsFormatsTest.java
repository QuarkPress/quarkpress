package org.quarkpress.media.image.vips.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.libvips.Version;

/** The answers of a probe result; the probe itself runs against libvips in {@code VipsFormatsDbTest}. */
class VipsFormatsTest {

    private static final VipsFormats ALL = new VipsFormats(
            Set.of("image/gif", "image/avif", "image/webp", "image/png", "image/jpeg"),
            Set.of("image/heif", "image/heic"),
            Set.of("image/tiff"));

    @Test
    void withoutLibvipsNothingIsSupported() {
        for (String type : new String[] {"image/jpeg", "image/png", "image/heic", "image/tiff"}) {
            assertThat(VipsFormats.NONE.supports(type)).as(type).isFalse();
            assertThat(VipsFormats.NONE.decodes(type)).as(type).isFalse();
            assertThat(VipsFormats.NONE.writes(type)).as(type).isFalse();
        }
    }

    @Test
    void writtenTypesAreDecodedAndWritten() {
        assertThat(ALL.supports("image/webp")).isTrue();
        assertThat(ALL.decodes("image/webp")).isTrue();
        assertThat(ALL.writes("image/webp")).isTrue();
    }

    @Test
    void readTypesAreDecodedButNeverWritten() {
        assertThat(ALL.supports("image/heic")).isTrue();
        assertThat(ALL.decodes("image/heic")).isTrue();
        assertThat(ALL.writes("image/heic")).isFalse();
    }

    @Test
    void acceptedTypesAreNeitherDecodedNorWritten() {
        assertThat(ALL.supports("image/tiff")).isTrue();
        assertThat(ALL.decodes("image/tiff")).isFalse();
        assertThat(ALL.writes("image/tiff")).isFalse();
    }

    @Test
    void otherTypesAreNotSupported() {
        for (String type : new String[] {"image/bmp", "image/x-icon", "image/heic-sequence", "image/svg+xml"}) {
            assertThat(ALL.supports(type)).as(type).isFalse();
        }
    }

    @Test
    void theStartUpLineNamesEveryTypeInOrder() {
        assertThat(ALL.describe(new Version(8, 18, 7)))
                .isEqualTo("libvips 8.18.7: sub-sizes in image/jpeg, image/png, image/webp, image/avif, image/gif;"
                        + " reads image/heic, image/heif; image/tiff accepted");
    }

    @Test
    void theStartUpLineNamesThePackagesOfMissingCodecs() {
        VipsFormats noble =
                new VipsFormats(Set.of("image/jpeg", "image/png", "image/webp", "image/gif"), Set.of(), Set.of());
        assertThat(noble.describe(new Version(8, 15, 1)))
                .isEqualTo("libvips 8.15.1: sub-sizes in image/jpeg, image/png, image/webp, image/gif;"
                        + " image/avif is not written: install libheif-plugin-aomenc;"
                        + " image/heic is not read: install libheif-plugin-libde265");
        assertThat(VipsFormats.NONE.describe(new Version(8, 15, 1)))
                .startsWith("libvips 8.15.1: sub-sizes in nothing;");
    }

    @Test
    void theSetsAreCopies() {
        java.util.HashSet<String> writable = new java.util.HashSet<>(Set.of("image/jpeg"));
        VipsFormats formats = new VipsFormats(writable, Set.of(), Set.of());
        writable.add("image/png");
        assertThat(formats.writes("image/png")).isFalse();
    }
}
