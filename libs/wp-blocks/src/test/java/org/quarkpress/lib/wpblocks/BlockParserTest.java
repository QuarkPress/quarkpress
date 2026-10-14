package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlockParserTest {

    @Test
    void emptyInputIsEmptyList() {
        assertThat(WpBlocks.parse("")).isEmpty();
    }

    @Test
    void contentWithoutDelimitersIsOneFreeformBlock() {
        List<Block> blocks = WpBlocks.parse("<p>Hello</p>\n<!-- a comment -->\n");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isFreeform()).isTrue();
        assertThat(blocks.get(0).innerHtml()).isEqualTo("<p>Hello</p>\n<!-- a comment -->\n");
    }

    @Test
    void simpleBlockKeepsSurroundingNewlinesInsideIt() {
        List<Block> blocks = WpBlocks.parse("<!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph -->");
        assertThat(blocks).hasSize(1);
        Block p = blocks.get(0);
        assertThat(p.name()).isEqualTo("core/paragraph");
        assertThat(p.attributes().isEmpty()).isTrue();
        assertThat(p.rawAttributes()).isEmpty();
        assertThat(p.innerHtml()).isEqualTo("\n<p>Hi</p>\n");
        assertThat(p.innerContent()).containsExactly("\n<p>Hi</p>\n");
    }

    @Test
    void voidBlockHasEmptyContentAndParsedAttributes() {
        List<Block> blocks = WpBlocks.parse(
                "<!-- wp:core/block {\"ref\":123,\"content\":{\"Nice paragraph\":{\"content\":\"Some value\"}}} /-->");
        Block b = blocks.get(0);
        assertThat(b.name()).isEqualTo("core/block");
        assertThat(b.attributes().get("ref").intValue()).isEqualTo(123);
        assertThat(b.attributes()
                        .get("content")
                        .get("Nice paragraph")
                        .get("content")
                        .textValue())
                .isEqualTo("Some value");
        assertThat(b.rawAttributes())
                .contains("{\"ref\":123,\"content\":{\"Nice paragraph\":{\"content\":\"Some value\"}}}");
        assertThat(b.innerHtml()).isEmpty();
        assertThat(b.innerContent()).isEmpty();
        assertThat(b.innerBlocks()).isEmpty();
    }

    @Test
    void nestedBlocksProduceNullPlaceholdersAndStrippedInnerHtml() {
        String doc = "<!-- wp:columns {\"backgroundColor\":\"secondary\"} -->\n"
                + "<div class=\"wp-block-columns\">\n"
                + "\t<!-- wp:column -->\n"
                + "\t<div class=\"wp-block-column\">\n"
                + "\t\t<!-- wp:paragraph -->\n"
                + "\t\t<p>One</p>\n"
                + "\t\t<!-- /wp:paragraph -->\n"
                + "\t\t<!-- wp:paragraph -->\n"
                + "\t\t<p>Two</p>\n"
                + "\t\t<!-- /wp:paragraph -->\n"
                + "\t</div>\n"
                + "\t<!-- /wp:column -->\n"
                + "</div>\n"
                + "<!-- /wp:columns -->";
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).hasSize(1);
        Block columns = blocks.get(0);
        assertThat(columns.name()).isEqualTo("core/columns");
        assertThat(columns.attributes().get("backgroundColor").textValue()).isEqualTo("secondary");
        assertThat(columns.innerBlocks()).hasSize(1);
        assertThat(columns.innerHtml()).isEqualTo("\n<div class=\"wp-block-columns\">\n\t\n</div>\n");
        assertThat(columns.innerContent())
                .containsExactly("\n<div class=\"wp-block-columns\">\n\t", null, "\n</div>\n");
        Block column = columns.innerBlocks().get(0);
        assertThat(column.innerBlocks()).extracting(Block::name).containsExactly("core/paragraph", "core/paragraph");
        assertThat(column.innerContent())
                .containsExactly("\n\t<div class=\"wp-block-column\">\n\t\t", null, "\n\t\t", null, "\n\t</div>\n\t");
        assertThat(column.innerBlocks().get(1).innerHtml()).isEqualTo("\n\t\t<p>Two</p>\n\t\t");
    }

    @Test
    void adjacentInnerBlocksAddNoEmptyChunk() {
        Block g = WpBlocks.parse("<!-- wp:group --><!-- wp:a /--><!-- wp:b /--><!-- /wp:group -->")
                .get(0);
        assertThat(g.innerContent()).containsExactly(null, null);
        assertThat(g.innerHtml()).isEmpty();
        assertThat(g.innerBlocks()).extracting(Block::name).containsExactly("core/a", "core/b");
    }

    @Test
    void textBetweenTopLevelBlocksIsFreeformIncludingWhitespaceOnly() {
        List<Block> blocks = WpBlocks.parse("<!-- wp:a /-->\n\n<!-- wp:b /-->\n");
        assertThat(blocks).extracting(Block::name).containsExactly("core/a", null, "core/b", null);
        assertThat(blocks.get(1).innerHtml()).isEqualTo("\n\n");
        assertThat(blocks.get(3).innerHtml()).isEqualTo("\n");
    }

    @Test
    void classicContentAroundBlocksIsFreeform() {
        List<Block> blocks = WpBlocks.parse("<p>before</p><!-- wp:a /--><p>after</p>");
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/a", null);
        assertThat(blocks.get(0).innerHtml()).isEqualTo("<p>before</p>");
        assertThat(blocks.get(2).innerHtml()).isEqualTo("<p>after</p>");
    }

    @Test
    void nearMissDelimitersAreText() {
        String doc = "<!-- wp:Paragraph -->x<!--wp:a -->y<!-- wp:core/4-invalid /-->z<!-- wp:a {\"k\":1}-->";
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isFreeform()).isTrue();
        assertThat(blocks.get(0).innerHtml()).isEqualTo(doc);
    }

    @Test
    void closerWithoutOpenerIsText() {
        List<Block> blocks = WpBlocks.parse("a<!-- /wp:paragraph -->b");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).innerHtml()).isEqualTo("a<!-- /wp:paragraph -->b");
    }

    @Test
    void closersMatchByPositionNotByName() {
        Block outer = WpBlocks.parse("<!-- wp:a --><!-- wp:b -->x<!-- /wp:a -->y<!-- /wp:b -->")
                .get(0);
        assertThat(outer.name()).isEqualTo("core/a");
        assertThat(outer.innerBlocks()).hasSize(1);
        assertThat(outer.innerBlocks().get(0).name()).isEqualTo("core/b");
        assertThat(outer.innerBlocks().get(0).innerHtml()).isEqualTo("x");
        assertThat(outer.innerContent()).containsExactly(null, "y");
    }

    @Test
    void unclosedBlocksAreClosedAtEndOfInput() {
        List<Block> blocks = WpBlocks.parse("<!-- wp:group --><p>x</p><!-- wp:a -->tail");
        assertThat(blocks).hasSize(1);
        Block group = blocks.get(0);
        assertThat(group.innerBlocks()).hasSize(1);
        assertThat(group.innerContent()).containsExactly("<p>x</p>", null);
        assertThat(group.innerBlocks().get(0).innerHtml()).isEqualTo("tail");
    }

    @Test
    void truncatedOpenerIsText() {
        List<Block> blocks = WpBlocks.parse("<!-- wp:a {\"k\":");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).innerHtml()).isEqualTo("<!-- wp:a {\"k\":");
    }

    @Test
    void invalidAttributeJsonKeepsRawTextAndEmptyAttributes() {
        Block b = WpBlocks.parse("<!-- wp:a {\"k\":} /-->").get(0);
        assertThat(b.attributes().isEmpty()).isTrue();
        assertThat(b.rawAttributes()).contains("{\"k\":}");
    }

    @Test
    void overlappingCommentOpenersAreHandled() {
        // the first "<!--" is not a delimiter; the second one, starting 3 chars later, is
        List<Block> blocks = WpBlocks.parse("<!-<!-- wp:a /-->");
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/a");
        assertThat(blocks.get(0).innerHtml()).isEqualTo("<!-");
    }
}
