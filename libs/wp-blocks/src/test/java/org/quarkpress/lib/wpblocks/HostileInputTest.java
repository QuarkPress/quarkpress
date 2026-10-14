package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final String[] INPUTS = {
        "<!-- wp:a {".repeat(100_000), "<!-- wp:a {\"".repeat(100_000), "<!-- wp:a {} ".repeat(100_000)
    };

    @Test
    void parseIsLinearOnUnterminatedAttributeCandidates() {
        for (String doc : INPUTS) {
            List<Block> blocks = assertTimeoutPreemptively(LIMIT, () -> WpBlocks.parse(doc));
            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).isFreeform()).isTrue();
            assertThat(blocks.get(0).innerHtml()).isEqualTo(doc);
        }
    }

    @Test
    void scannerWithSharedMemoIsLinear() {
        for (String doc : INPUTS) {
            assertTimeoutPreemptively(LIMIT, () -> {
                AttributeEnds ends = new AttributeEnds(doc);
                for (int i = 0; i < doc.length(); i++) {
                    assertThat(DelimiterScanner.tryRead(doc, i, ends)).isNull();
                }
            });
        }
    }

    @Test
    void hasBlocksIsLinearOnUnterminatedAttributeCandidates() {
        for (String doc : INPUTS) {
            boolean found = assertTimeoutPreemptively(LIMIT, () -> WpBlocks.hasBlocks(doc));
            assertThat(found).isFalse();
        }
    }
}
