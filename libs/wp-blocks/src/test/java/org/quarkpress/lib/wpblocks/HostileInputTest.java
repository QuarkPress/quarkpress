package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final String[] INPUTS = {
        "<!-- wp:a {".repeat(100_000),
        "<!-- wp:a {\"".repeat(100_000),
        "<!-- wp:a {} ".repeat(100_000),
        "<!-- /wp:a {".repeat(100_000),
        "<!--\f/wp:a\f{} ".repeat(100_000)
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

    @Test
    void parseAllocatesLinearlyOnDeepAttributesWithLongKeys() {
        // Each nesting level adds a long key, so anything proportional to the key path per node
        // (e.g. building "$.k…k.k…k" eagerly) allocates gigabytes. Wall time hides it; allocation does not.
        String key = "k".repeat(4_000);
        int depth = 900;
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            json.append("{\"").append(key).append("\":");
        }
        json.append('1').append("}".repeat(depth));
        String doc = "<!-- wp:a " + json + " /-->";

        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        List<Block> blocks = WpBlocks.parse(doc);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).name()).isEqualTo("core/a");
        assertThat(allocated).isLessThan(50L * doc.length());
    }
}
