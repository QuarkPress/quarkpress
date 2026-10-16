package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Properties;

class FuzzSmokeTest {

    @Test
    void parserNeverThrowsWithDefaultLimits() {
        Properties.check(
                "FuzzSmokeTest.parserNeverThrowsWithDefaultLimits", 3000, BlockMarkupArbitraries.commentSoup(), doc -> {
                    assertThatCode(() -> WpBlocks.parse(doc)).doesNotThrowAnyException();
                    assertThatCode(() -> WpBlocks.hasBlocks(doc)).doesNotThrowAnyException();
                });
    }

    @Test
    void parserThrowsOnlyBlockParseExceptionWithTinyLimits() {
        ParseOptions tiny = ParseOptions.DEFAULTS.withMaxDepth(2).withMaxBlocks(3);
        Properties.check(
                "FuzzSmokeTest.parserThrowsOnlyBlockParseExceptionWithTinyLimits",
                3000,
                BlockMarkupArbitraries.commentSoup(),
                doc -> {
                    try {
                        List<Block> blocks = WpBlocks.parse(doc, tiny);
                        assertThat(blocks.size()).isLessThanOrEqualTo(3);
                    } catch (BlockParseException expected) {
                        assertThat(expected.offset()).isBetween(0, doc.length());
                    }
                });
    }
}
