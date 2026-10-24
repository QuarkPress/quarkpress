package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RevisionRecordsTest {

    static final List<Long> CREATED = List.of(50L, 52L, 55L);
    static final Map<Long, Integer> POSTS = Map.of(40L, 1, 41L, 2);
    static final Map<Long, Integer> USERS = Map.of(5L, 1, 6L, 2);
    static final RunWindow WINDOW =
            new RunWindow(Instant.parse("2026-10-22T10:00:00Z"), Instant.parse("2026-10-22T10:05:00Z"));

    @Test
    void idsAreNamedByCreationOrderOrAsPosts() {
        assertThat(RevisionRecords.ref(52, CREATED, POSTS)).isEqualTo("r2");
        assertThat(RevisionRecords.ref(41, CREATED, POSTS)).isEqualTo("p2");
        assertThat(RevisionRecords.ref(99, CREATED, POSTS)).isEqualTo("#?99");
    }

    @Test
    void aRecordNamesItsPostAndAuthorAndShowsRunTimeDatesAsNow() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 55L);
        raw.put("parent", 40L);
        raw.put("author", 6L);
        raw.put("autosave", true);
        raw.put("title", "T");
        raw.put("content", "Body");
        raw.put("excerpt", "");
        raw.put("footnotes", List.of("[1]", "[2]"));
        raw.put("date_gmt", "2026-10-22 10:02:30");
        assertThat(RevisionRecords.record(raw, CREATED, POSTS, USERS, 1L, WINDOW)
                        .fields())
                .containsExactly(
                        Map.entry("parent", "p1"),
                        Map.entry("author", "u2"),
                        Map.entry("autosave", "1"),
                        Map.entry("title", "T"),
                        Map.entry("content", "Body"),
                        Map.entry("excerpt", ""),
                        Map.entry("footnotes", "[1]|[2]"),
                        Map.entry("date_gmt", "now"));
        raw.put("author", 0L);
        raw.put("footnotes", List.of());
        raw.put("date_gmt", "2020-01-01 10:00:00");
        assertThat(RevisionRecords.record(raw, CREATED, POSTS, USERS, 1L, WINDOW)
                        .fields())
                .containsEntry("author", "none")
                .containsEntry("footnotes", "(none)")
                .containsEntry("date_gmt", "2020-01-01 10:00:00");
    }
}
