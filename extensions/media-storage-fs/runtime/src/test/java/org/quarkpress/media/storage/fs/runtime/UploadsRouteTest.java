package org.quarkpress.media.storage.fs.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The request parsing of the uploads route: byte ranges, HTTP dates, keys from the raw path. */
class UploadsRouteTest {

    @Test
    void singleByteRanges() {
        assertThat(UploadsRoute.range("bytes=0-9", 100)).isEqualTo(new UploadsRoute.Range(0, 9));
        assertThat(UploadsRoute.range("bytes=90-", 100)).isEqualTo(new UploadsRoute.Range(90, 99));
        assertThat(UploadsRoute.range("bytes=-10", 100)).isEqualTo(new UploadsRoute.Range(90, 99));
        assertThat(UploadsRoute.range("bytes=-500", 100)).isEqualTo(new UploadsRoute.Range(0, 99));
        assertThat(UploadsRoute.range("bytes=50-500", 100)).isEqualTo(new UploadsRoute.Range(50, 99));
        assertThat(UploadsRoute.range("bytes=99-99", 100)).isEqualTo(new UploadsRoute.Range(99, 99));
        assertThat(UploadsRoute.range(" bytes= 5 - 6 ", 100)).isEqualTo(new UploadsRoute.Range(5, 6));
    }

    @Test
    void unsatisfiableRanges() {
        assertThat(UploadsRoute.range("bytes=100-", 100)).isSameAs(UploadsRoute.Range.UNSATISFIABLE);
        assertThat(UploadsRoute.range("bytes=100-200", 100)).isSameAs(UploadsRoute.Range.UNSATISFIABLE);
        assertThat(UploadsRoute.range("bytes=-0", 100)).isSameAs(UploadsRoute.Range.UNSATISFIABLE);
        assertThat(UploadsRoute.range("bytes=0-", 0)).isSameAs(UploadsRoute.Range.UNSATISFIABLE);
        assertThat(UploadsRoute.range("bytes=-5", 0)).isSameAs(UploadsRoute.Range.UNSATISFIABLE);
    }

    @Test
    void otherRangeHeadersSendTheWholeFile() {
        for (String header : List.of(
                "bytes=0-1,5-6",
                "bytes=9-5",
                "bytes=-",
                "bytes=5",
                "bytes=a-b",
                "bytes=-x",
                "bytes=+1-2",
                "items=0-1",
                "bytes=1234567890123456789-")) {
            assertThat(UploadsRoute.range(header, 100)).as(header).isNull();
        }
        assertThat(UploadsRoute.range(null, 100)).isNull();
    }

    @Test
    void theUnitIsMatchedWithoutRegardToCase() {
        assertThat(UploadsRoute.range("Bytes=0-1", 100)).isEqualTo(new UploadsRoute.Range(0, 1));
        assertThat(UploadsRoute.range("BYTES=-2", 100)).isEqualTo(new UploadsRoute.Range(98, 99));
        assertThat(UploadsRoute.range("bytez=0-1", 100)).isNull();
    }

    @Test
    void ifRangeKeepsTheRangeOnlyForTheCurrentDate() {
        Instant modified = Instant.parse("2026-11-05T12:00:00.750Z");
        UploadsRoute.Range part = new UploadsRoute.Range(0, 9);
        assertThat(UploadsRoute.rangeIfCurrent("bytes=0-9", null, modified, 100))
                .isEqualTo(part);
        assertThat(UploadsRoute.rangeIfCurrent("bytes=0-9", "Thu, 05 Nov 2026 12:00:00 GMT", modified, 100))
                .isEqualTo(part);
        assertThat(UploadsRoute.rangeIfCurrent("bytes=0-9", "Thu, 05 Nov 2026 11:59:59 GMT", modified, 100))
                .isNull();
        assertThat(UploadsRoute.rangeIfCurrent("bytes=0-9", "Thu, 05 Nov 2026 12:00:01 GMT", modified, 100))
                .isNull();
        assertThat(UploadsRoute.rangeIfCurrent("bytes=0-9", "\"abc\"", modified, 100))
                .isNull();
        assertThat(UploadsRoute.rangeIfCurrent("bytes=100-", "\"abc\"", modified, 100))
                .isNull();
    }

    @Test
    void aFutureIfModifiedSinceIsIgnored() {
        Instant modified = Instant.parse("2026-11-05T12:00:00Z");
        Instant now = Instant.parse("2026-11-05T13:00:00Z");
        assertThat(UploadsRoute.notModified(modified, Optional.of(modified), now))
                .isTrue();
        assertThat(UploadsRoute.notModified(modified, Optional.of(now), now)).isTrue();
        assertThat(UploadsRoute.notModified(modified, Optional.of(now.plusSeconds(1)), now))
                .isFalse();
        assertThat(UploadsRoute.notModified(modified, Optional.of(modified.minusSeconds(1)), now))
                .isFalse();
        assertThat(UploadsRoute.notModified(modified, Optional.empty(), now)).isFalse();
    }

    @Test
    void httpDates() {
        Instant at = Instant.parse("2026-11-05T12:00:00Z");
        assertThat(UploadsRoute.httpDate(at)).isEqualTo("Thu, 05 Nov 2026 12:00:00 GMT");
        assertThat(UploadsRoute.parseHttpDate("Thu, 05 Nov 2026 12:00:00 GMT")).contains(at);
        assertThat(UploadsRoute.parseHttpDate("yesterday")).isEmpty();
        assertThat(UploadsRoute.parseHttpDate(null)).isEmpty();
    }

    @Test
    void keysAreDecodedOnceFromTheRawPath() {
        List<String> prefix = UploadsRoute.segments("/wp-content/uploads");
        assertThat(UploadsRoute.rawKey("/wp-content/uploads/2026/11/%2541.jpg", prefix))
                .contains("2026/11/%2541.jpg");
        assertThat(UploadsRoute.servedKey("2026/11/%2541.jpg")).contains("2026/11/%41.jpg");
        assertThat(UploadsRoute.servedKey("2026/11/.hidden")).isEmpty();
        assertThat(UploadsRoute.servedKey("2026/11/%2ehidden")).isEmpty();
        assertThat(UploadsRoute.rawKey("/a/b/c/d/wp-content/uploads/x", prefix)).contains("x");
        assertThat(UploadsRoute.rawKey("/a/b/c/d/e/wp-content/uploads/x", prefix))
                .isEmpty();
    }
}
