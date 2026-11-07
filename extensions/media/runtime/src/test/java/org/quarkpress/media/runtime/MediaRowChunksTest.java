package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The media rows of a page are loaded by at most {@link JpaMedia#ROW_CHUNK} ids per query. */
class MediaRowChunksTest {

    private static List<Long> ids(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    @Test
    void idsAreLoadedInChunksOfAtMostTheChunkSize() {
        assertThat(JpaMedia.ROW_CHUNK).isEqualTo(1000);
        List<List<Long>> chunks = JpaMedia.chunks(ids(2500), JpaMedia.ROW_CHUNK);
        assertThat(chunks).extracting(List::size).containsExactly(1000, 1000, 500);
        List<Long> joined = new ArrayList<>();
        chunks.forEach(joined::addAll);
        assertThat(joined).isEqualTo(ids(2500));

        assertThat(JpaMedia.chunks(ids(1000), 1000)).extracting(List::size).containsExactly(1000);
        assertThat(JpaMedia.chunks(ids(1001), 1000)).extracting(List::size).containsExactly(1000, 1);
        assertThat(JpaMedia.chunks(ids(5), 2)).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(JpaMedia.chunks(List.of(), 2)).isEmpty();
    }
}
