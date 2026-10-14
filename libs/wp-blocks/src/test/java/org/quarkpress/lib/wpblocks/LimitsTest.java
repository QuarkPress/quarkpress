package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void depthBeyondMaxDepthThrowsWithOffsetOfOffendingOpener() {
        String doc = "<!-- wp:a -->".repeat(70);
        assertThatThrownBy(() -> WpBlocks.parse(doc))
                .isInstanceOf(BlockParseException.class)
                .hasMessageContaining("depth")
                .hasMessageContaining("at offset " + (64 * "<!-- wp:a -->".length()));
        assertThat(WpBlocks.parse(doc, ParseOptions.DEFAULTS.withMaxDepth(70))).hasSize(1);
    }

    @Test
    void blockCountBeyondMaxBlocksThrows() {
        String doc = "<!-- wp:a /-->".repeat(11);
        ParseOptions ten = ParseOptions.DEFAULTS.withMaxBlocks(10);
        assertThatThrownBy(() -> WpBlocks.parse(doc, ten))
                .isInstanceOf(BlockParseException.class)
                .hasMessageContaining("blocks")
                .hasMessageContaining("at offset " + (10 * "<!-- wp:a /-->".length()));
        assertThat(WpBlocks.parse("<!-- wp:a /-->".repeat(10), ten)).hasSize(10);
    }

    @Test
    void freeformBlocksCountTowardsMaxBlocks() {
        ParseOptions two = ParseOptions.DEFAULTS.withMaxBlocks(2);
        assertThat(WpBlocks.parse("x<!-- wp:a /-->", two)).hasSize(2);
        assertThatThrownBy(() -> WpBlocks.parse("x<!-- wp:a /-->y", two)).isInstanceOf(BlockParseException.class);
    }

    @Test
    void exceptionCarriesOffset() {
        BlockParseException e = new BlockParseException("too deep", 42);
        assertThat(e.offset()).isEqualTo(42);
        assertThat(e.getMessage()).isEqualTo("too deep at offset 42");
    }

    @Test
    void optionsValidateRanges() {
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxDepth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxDepth(ParseOptions.MAX_DEPTH_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxBlocks(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ParseOptions.DEFAULTS.maxDepth()).isEqualTo(64);
        assertThat(ParseOptions.DEFAULTS.maxBlocks()).isEqualTo(100_000);
    }
}
