package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class FuzzSmokeTest {

    @Property(tries = 3000)
    void parserNeverThrowsWithDefaultLimits(@ForAll("commentSoup") String doc) {
        assertThatCode(() -> WpBlocks.parse(doc)).doesNotThrowAnyException();
        assertThatCode(() -> WpBlocks.hasBlocks(doc)).doesNotThrowAnyException();
    }

    @Property(tries = 3000)
    void parserThrowsOnlyBlockParseExceptionWithTinyLimits(@ForAll("commentSoup") String doc) {
        ParseOptions tiny = ParseOptions.DEFAULTS.withMaxDepth(2).withMaxBlocks(3);
        try {
            List<Block> blocks = WpBlocks.parse(doc, tiny);
            assertThat(blocks.size()).isLessThanOrEqualTo(3);
        } catch (BlockParseException expected) {
            assertThat(expected.offset()).isBetween(0, doc.length());
        }
    }

    /** Strings glued from delimiter fragments, so that many near-delimiters occur. */
    @Provide
    Arbitrary<String> commentSoup() {
        Arbitrary<String> fragment = Arbitraries.of(
                "<!--",
                " wp:",
                " /wp:",
                "a",
                "b/c",
                "core/x",
                "{",
                "}",
                "\"",
                "\\",
                ":",
                "1",
                " ",
                "\n",
                "-->",
                "/-->",
                "<p>",
                "--",
                "<!-- wp:a -->",
                "<!-- /wp:a -->",
                "<!-- wp:a /-->",
                "<!-- wp:a {\"k\":\"}\"} -->");
        return fragment.list().ofMaxSize(40).map(l -> String.join("", l));
    }
}
