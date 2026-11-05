package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/** The facts run's helpers that need no WordPress: the size-file path, the JPEG frame marker, {@code JpegMeta}. */
class MediaFactsHelpersTest {

    @Test
    void aSubdirectorySitesUploadUrlIsFetchedWithoutTheSitePrefix() {
        assertThat(MediaFactsDbTest.uploadPath(
                        "http://localhost/mi14/wp-content/uploads/sites/4/2026/11/a-300x225.jpg"))
                .isEqualTo("/wp-content/uploads/sites/4/2026/11/a-300x225.jpg");
        assertThat(MediaFactsDbTest.uploadPath("http://localhost/wp-content/uploads/2026/11/a.jpg"))
                .isEqualTo("/wp-content/uploads/2026/11/a.jpg");
    }

    @Test
    void theFrameMarkerIsReadFromTheSegmentsBeforeTheScan() {
        assertThat(MediaFactsDbTest.jpegFrameMarker(MediaFixtures.jpeg(16, 16))).isEqualTo("SOF0");
        assertThat(MediaFactsDbTest.jpegFrameMarker(MediaFixtures.progressiveJpeg(16, 16)))
                .isEqualTo("SOF2");
        byte[] withIcc = MediaFixtures.jpeg(16, 16, JpegMeta.none().withIcc(new byte[] {(byte) 0xFF, (byte) 0xC2}));
        assertThat(MediaFactsDbTest.jpegFrameMarker(withIcc))
                .as("FF C2 inside a segment's payload is not a frame marker")
                .isEqualTo("SOF0");
        assertThat(MediaFactsDbTest.jpegFrameMarker(MediaFixtures.png(4, 4))).isEqualTo("none");
    }

    @Test
    void jpegMetaIsImmutableAndComparesTheProfileByItsBytes() {
        List<String> values = new ArrayList<>(List.of("a"));
        JpegMeta meta = JpegMeta.none().withIptc(25, "k").withIcc(new byte[] {1, 2});
        JpegMeta copied = new JpegMeta(meta.orientation(), Map.of(25, values), meta.exif(), meta.xmp(), meta.icc());
        values.add("b");
        assertThat(copied.iptc().get(25)).containsExactly("a");
        assertThatThrownBy(() -> copied.iptc().put(1, List.of())).isInstanceOf(UnsupportedOperationException.class);
        meta.icc().orElseThrow()[0] = 9;
        assertThat(meta.icc().orElseThrow()[0]).isEqualTo((byte) 1);
        assertThat(JpegMeta.none().withIcc(new byte[] {1, 2}))
                .isEqualTo(JpegMeta.none().withIcc(new byte[] {1, 2}));
        assertThat(JpegMeta.none().withIcc(new byte[] {1, 2}).hashCode())
                .isEqualTo(JpegMeta.none().withIcc(new byte[] {1, 2}).hashCode());
    }
}
