package org.quarkpress.media.image.vips.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The type of an image is told by the libvips loader that read it (real loads: {@code VipsImageProcessorDbTest}). */
class VipsTypesTest {

    @ParameterizedTest
    @CsvSource(
            nullValues = "-",
            value = {
                "jpegload,-,image/jpeg",
                "jpegload_buffer,-,image/jpeg",
                "jpegload_source,-,image/jpeg",
                "pngload,-,image/png",
                "pngload_buffer,-,image/png",
                "gifload,-,image/gif",
                "gifload_source,-,image/gif",
                "webpload,-,image/webp",
                "webpload_buffer,-,image/webp",
                "tiffload,-,image/tiff",
                "heifload,av1,image/avif",
                "heifload_buffer,av1,image/avif",
                "heifload,hevc,image/heic",
                "heifload,jpeg,image/heif",
                "heifload,-,image/heif",
                "ppmload,-,-",
                "svgload,-,-",
                "magickload,-,-",
                "matrixload,-,-",
                "jp2kload,-,-",
                "jpegxlload,-,-",
                "csvload,-,-",
                "pngloadx,-,-",
                "xjpegload,-,-",
                "'',-,-"
            })
    void loadersMapToWordPressTypes(String loader, String compression, String expected) {
        assertThat(VipsTypes.mimeOf(loader, Optional.ofNullable(compression))).isEqualTo(Optional.ofNullable(expected));
    }
}
