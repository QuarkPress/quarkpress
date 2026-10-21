package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TermRecordsTest {

    static final List<Long> CREATED = List.of(10L, 12L, 15L);
    static final Map<Long, String> DEFAULTS = Map.of(1L, "default-category", 2L, "default-genre");

    @Test
    void idsAreNamedByCreationOrderAndDefaults() {
        assertThat(TermRecords.termRef(12, CREATED, DEFAULTS)).isEqualTo("t2");
        assertThat(TermRecords.termRef(1, CREATED, DEFAULTS)).isEqualTo("default-category");
        assertThat(TermRecords.termRef(0, CREATED, DEFAULTS)).isEqualTo("none");
        assertThat(TermRecords.termRef(99, CREATED, DEFAULTS)).isEqualTo("#?99");
    }

    @Test
    void aSlugThatIsTheTermsOwnIdIsSelf() {
        assertThat(TermRecords.slug("15", 15)).isEqualTo("#self");
        assertThat(TermRecords.slug("15-2", 15)).isEqualTo("#self-2");
        assertThat(TermRecords.slug("1500", 15)).isEqualTo("1500");
        assertThat(TermRecords.slug("news", 15)).isEqualTo("news");
        assertThat(TermRecords.slug("15-2-3", 15)).isEqualTo("#self-2-3");
        assertThat(TermRecords.slug("15-days", 15)).isEqualTo("15-days");
        assertThat(TermRecords.slug("15-2-x", 15)).isEqualTo("15-2-x");
        assertThat(TermRecords.slug("15-", 15)).isEqualTo("15-");
        assertThat(TermRecords.slug("15x", 15)).isEqualTo("15x");
    }

    @Test
    void aRecordNamesItsParentAndKeepsItsText() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 15L);
        raw.put("taxonomy", "category");
        raw.put("name", "Rock &amp; Roll");
        raw.put("slug", "rock-roll");
        raw.put("description", "");
        raw.put("parent", 10L);
        raw.put("count", 3L);
        assertThat(TermRecords.record(raw, CREATED, DEFAULTS).fields())
                .containsExactly(
                        Map.entry("taxonomy", "category"),
                        Map.entry("name", "Rock &amp; Roll"),
                        Map.entry("slug", "rock-roll"),
                        Map.entry("description", ""),
                        Map.entry("parent", "t1"),
                        Map.entry("count", "3"));
    }

    @Test
    void unorderedRefsSortDefaultsFirstThenByIndex() {
        assertThat(TermRecords.sortedRefs(List.of("t10", "default-genre", "t2", "default-category", "#?5")))
                .containsExactly("default-category", "default-genre", "t2", "t10", "#?5");
    }
}
