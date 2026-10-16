package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void depthBeyondMaxDepthThrowsWithOffsetOfOffendingOpener() {
        String doc = "<!-- wp:a -->".repeat(70) + "<!-- /wp:a -->".repeat(70);
        assertThatThrownBy(() -> WpBlocks.parse(doc, ParseOptions.DEFAULTS.withMaxDepth(64)))
                .isInstanceOf(BlockParseException.class)
                .hasMessageContaining("depth")
                .hasMessageContaining("at offset " + (64 * "<!-- wp:a -->".length()));
        assertThat(WpBlocks.parse(doc, ParseOptions.DEFAULTS.withMaxDepth(70))).hasSize(1);
    }

    @Test
    void defaultsAcceptWhatWordPressAccepts() { // D19: WordPress has no limits; the defaults sit far above real posts
        String deep = "<!-- wp:a -->".repeat(1000) + "x" + "<!-- /wp:a -->".repeat(1000);
        assertThat(WpBlocks.parse(deep)).hasSize(1);
        assertThat(WpBlocks.parse("<!-- wp:a /-->".repeat(200_000))).hasSize(200_000);
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
    void manyBlocksLeftOpenBeforeLongTextFailFastWithoutCopyingIt() { // each recovered copy repeats the rest
        String doc = "<!-- wp:a -->".repeat(1024) + "x".repeat(1_000_000);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        assertThatThrownBy(() -> WpBlocks.parse(doc))
                .isInstanceOf(BlockParseException.class)
                .hasMessageContaining("maxRecoveredChars 8000000");
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertThat(allocated).isLessThan(64L * 1024 * 1024);
    }

    @Test
    void recoveredCopiesUnderTheLimitKeepWordPressShape() {
        String doc = "p<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z";
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/b", null, "core/a");
        assertThat(blocks).extracting(Block::isRecoveredCopy).containsExactly(true, true, false, false);
        assertThat(WpBlocks.serialize(blocks)).isEqualTo(doc);
        String wide = "<!-- wp:a -->".repeat(8) + "x".repeat(1_000_000); // 7 copies of about 1 MB each
        assertThat(WpBlocks.parse(wide)).hasSize(8);
    }

    @Test
    void maxRecoveredCharsIsConfigurable() {
        String doc = "p<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z"; // the copies repeat "x" and "z"
        assertThat(WpBlocks.parse(doc, ParseOptions.DEFAULTS.withMaxRecoveredChars(2)))
                .hasSize(4);
        assertThatThrownBy(() -> WpBlocks.parse(doc, ParseOptions.DEFAULTS.withMaxRecoveredChars(1)))
                .isInstanceOf(BlockParseException.class)
                .hasMessageContaining("maxRecoveredChars 1 ")
                .hasMessageContaining("at offset " + doc.indexOf("<!-- wp:b"));
        ParseOptions none = ParseOptions.DEFAULTS.withMaxRecoveredChars(0);
        assertThat(WpBlocks.parse("<!-- wp:a -->x", none)).hasSize(1); // the outermost block is not a copy
        assertThat(none.maxDepth()).isEqualTo(1024);
        assertThat(none.maxBlocks()).isEqualTo(1_000_000);
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
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxRecoveredChars(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ParseOptions.DEFAULTS.maxDepth()).isEqualTo(1024);
        assertThat(ParseOptions.DEFAULTS.maxBlocks()).isEqualTo(1_000_000);
        assertThat(ParseOptions.DEFAULTS.maxRecoveredChars()).isEqualTo(8_000_000);
    }
}
