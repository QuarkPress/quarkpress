package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.image.vips.runtime.VipsFormats;
import org.sitenetsoft.quarkus.libvips.Libvips;

/** The start-up probe against the installed libvips. */
class VipsFormatsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            VipsApp.with(VipsFormatsDbTest.class).overrideConfigKey("quarkus.libvips.required", "true");

    @Inject
    Libvips libvips;

    @Test
    void theProbeAlwaysFindsJpegAndPng() {
        VipsFormats formats = VipsFormats.probe(libvips);
        assertThat(formats.writable()).contains("image/jpeg", "image/png");
        assertThat(formats.describe(libvips.version()))
                .startsWith("libvips " + libvips.version() + ": sub-sizes in image/jpeg, image/png");
    }

    @Test
    void theProbeFindsWhatThisLibvipsHas() {
        VipsFormats formats = VipsFormats.probe(libvips);
        assertThat(formats.writes("image/webp")).isEqualTo(libvips.hasOperation("webpsave_buffer"));
        assertThat(formats.writes("image/gif")).isEqualTo(libvips.hasOperation("gifsave_buffer"));
        if (!libvips.hasOperation("heifsave_buffer")) {
            assertThat(formats.writes("image/avif")).isFalse();
        }
        if (!libvips.hasOperation("heifload") || libvips.isBlocked("VipsForeignLoadHeifFile")) {
            assertThat(formats.decodes("image/heic")).isFalse();
        }
        assertThat(formats.readable()).isSubsetOf("image/heic", "image/heif");
        assertThat(formats.decodes("image/heic")).isEqualTo(formats.decodes("image/heif"));
        assertThat(formats.acceptedOnly()).isSubsetOf("image/tiff");
        assertThat(formats.writable()).doesNotContain("image/heic", "image/heif", "image/tiff", "image/bmp");
    }

    @Test
    void requiredFormatsArePresentInCi() {
        assumeTrue(Boolean.getBoolean("vips.require-all-formats"), "only with -Dvips.require-all-formats=true");
        VipsFormats formats = VipsFormats.probe(libvips);
        assertThat(formats.writable())
                .containsExactlyInAnyOrder("image/jpeg", "image/png", "image/webp", "image/avif", "image/gif");
        assertThat(formats.readable()).containsExactlyInAnyOrder("image/heic", "image/heif");
        assertThat(formats.acceptedOnly()).containsExactly("image/tiff");
    }

    @Test
    void theProbeLeavesNoImageOpen() {
        long before = libvips.openImages();
        VipsFormats.probe(libvips);
        assertThat(libvips.openImages()).isEqualTo(before);
    }
}
