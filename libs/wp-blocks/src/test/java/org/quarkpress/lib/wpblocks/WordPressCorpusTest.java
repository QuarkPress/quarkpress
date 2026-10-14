package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fixtures shaped like posts saved by WordPress. Written by hand from observed editor output
 * (clean-room); none of them is copied from WordPress or Gutenberg.
 */
class WordPressCorpusTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = WordPressCorpusTest.class.getResourceAsStream("/wp/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "post_paragraphs_headings.html",
                "post_columns_nested.html",
                "post_classic.html",
                "post_mixed_classic_and_blocks.html",
                "post_reusable_block.html",
                "post_broken_closer.html"
            })
    void everyFixtureRoundTripsByteIdentically(String name) throws IOException {
        String doc = fixture(name);
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
    }

    @Test
    void paragraphsAndHeadings() throws IOException {
        List<Block> blocks = WpBlocks.parse(fixture("post_paragraphs_headings.html"));
        List<String> names = blocks.stream().map(Block::name).toList();
        assertThat(names)
                .containsExactly(
                        "core/heading",
                        null,
                        "core/paragraph",
                        null,
                        "core/paragraph",
                        null,
                        "core/heading",
                        null,
                        "core/list",
                        null);
        assertThat(blocks.get(4)
                        .attributes()
                        .get("style")
                        .get("typography")
                        .get("fontSize")
                        .textValue())
                .isEqualTo("20px");
        assertThat(blocks.get(6).attributes().get("level").intValue()).isEqualTo(3);
        Block list = blocks.get(8);
        assertThat(list.innerBlocks()).extracting(Block::name).containsExactly("core/list-item", "core/list-item");
        assertThat(list.innerContent())
                .containsExactly("\n<ul class=\"wp-block-list\">", null, "\n\n", null, "</ul>\n");
        assertThat(WpBlocks.hasBlocks(fixture("post_paragraphs_headings.html"))).isTrue();
    }

    @Test
    void columnsNestFourLevels() throws IOException {
        List<Block> blocks = WpBlocks.parse(fixture("post_columns_nested.html"));
        assertThat(blocks).extracting(Block::name).containsExactly("core/columns", null);
        Block columns = blocks.get(0);
        assertThat(columns.innerBlocks()).extracting(Block::name).containsExactly("core/column", "core/column");
        Block right = columns.innerBlocks().get(1);
        assertThat(right.attributes().get("width").textValue()).isEqualTo("33.33%");
        Block group = right.innerBlocks().get(0);
        assertThat(group.name()).isEqualTo("core/group");
        assertThat(group.innerBlocks()).extracting(Block::name).containsExactly("core/spacer", "core/separator");
        assertThat(group.innerBlocks().get(0).attributes().get("height").textValue())
                .isEqualTo("40px");
    }

    @Test
    void classicPostIsOneFreeformBlockAndHasNoBlocks() throws IOException {
        String doc = fixture("post_classic.html");
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isFreeform()).isTrue();
        assertThat(blocks.get(0).innerHtml()).isEqualTo(doc);
        assertThat(WpBlocks.hasBlocks(doc)).isFalse();
    }

    @Test
    void mixedContentKeepsClassicPartsAsFreeform() throws IOException {
        List<Block> blocks = WpBlocks.parse(fixture("post_mixed_classic_and_blocks.html"));
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/paragraph", null);
        assertThat(blocks.get(0).innerHtml()).startsWith("<p>Legacy intro");
        assertThat(blocks.get(2).innerHtml()).contains("Legacy trailing");
    }

    @Test
    void reusableBlocksAreVoidWithRefAttribute() throws IOException {
        List<Block> blocks = WpBlocks.parse(fixture("post_reusable_block.html"));
        assertThat(blocks.get(0).name()).isEqualTo("core/block");
        assertThat(blocks.get(0).attributes().get("ref").intValue()).isEqualTo(42);
        assertThat(blocks.get(0).innerContent()).isEmpty();
        assertThat(blocks.get(2)
                        .attributes()
                        .get("content")
                        .get("Nice paragraph")
                        .get("content")
                        .textValue())
                .isEqualTo("Some value");
    }

    @Test
    void brokenCloserSwallowsTheRestIntoTheOpenGroup() throws IOException {
        List<Block> blocks = WpBlocks.parse(fixture("post_broken_closer.html"));
        assertThat(blocks).hasSize(1);
        Block group = blocks.get(0);
        assertThat(group.name()).isEqualTo("core/group");
        assertThat(group.innerBlocks()).extracting(Block::name).containsExactly("core/paragraph", "core/paragraph");
        assertThat(group.innerHtml()).endsWith("</div>\n\n\n");
    }
}
