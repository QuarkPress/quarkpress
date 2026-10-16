package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ParitySkipListTest {

    static final String TEXT = String.join(
            "\n",
            "# comment",
            "",
            "audit-23-* unserialize #23 enums are data",
            "audit-D5-zero-* * D5 zero chunks",
            "none - #29 not observable through PHP");

    @Test
    void parsesEntriesAndMatchesGlobs() {
        ParitySkipList skips = ParitySkipList.parse(TEXT, "skip.txt");
        assertThat(skips.find("audit-23-unknown", "unserialize").map(ParitySkipList.Entry::auditItem))
                .contains("#23");
        assertThat(skips.find("audit-23-unknown", "reserialize")).isEmpty();
        assertThat(skips.find("audit-D5-zero-only", "tree").map(ParitySkipList.Entry::reason))
                .contains("zero chunks");
        assertThat(skips.find("audit-D50", "tree")).isEmpty();
        assertThat(skips.find("none", "-")).isEmpty();
    }

    @Test
    void reportsStaleAndUnusedEntries() {
        ParitySkipList skips = ParitySkipList.parse(TEXT, "skip.txt");
        skips.record(skips.find("audit-23-x", "unserialize").orElseThrow(), false);
        assertThat(skips.problems())
                .hasSize(2)
                .anySatisfy(p -> assertThat(p).contains("line 3").contains("stale"))
                .anySatisfy(p -> assertThat(p).contains("line 4").contains("matches no comparison"));

        skips.record(skips.find("audit-23-y", "unserialize").orElseThrow(), true);
        skips.record(skips.find("audit-D5-zero-only", "tree").orElseThrow(), true);
        assertThat(skips.problems()).isEmpty();
    }

    @Test
    void rejectsMalformedLines() {
        assertThatThrownBy(() -> ParitySkipList.parse("audit-1 unserialize #1", "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s:1");
        assertThatThrownBy(() -> ParitySkipList.parse("audit-1 unserialize item-1 reason", "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#12 or D7");
    }

    @Test
    void globsTreatOtherCharactersLiterally() {
        ParitySkipList skips = ParitySkipList.parse("a.b * #1 dots are literal", "s");
        assertThat(skips.find("a.b", "x")).isPresent();
        assertThat(skips.find("axb", "x")).isEmpty();
    }
}
