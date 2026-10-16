package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
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
    void blocksLeftOpenAtTheEndAreClosedAsWordPressDoes() { // D2
        String doc = "<!-- wp:group --><p>x</p><!-- wp:a -->tail";
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/a", "core/group");
        assertThat(blocks.get(0).innerHtml()).isEqualTo("<p>x</p>");
        assertThat(blocks.get(1).innerHtml()).isEqualTo("tail");
        assertThat(blocks.get(2).innerContent()).containsExactly("<p>x</p><!-- wp:a -->tail");
        assertThat(blocks.get(2).innerBlocks()).isEmpty();
        assertThat(blocks).extracting(Block::isRecoveredCopy).containsExactly(true, true, false);
        assertThat(WpBlocks.serialize(blocks)).isEqualTo(doc);
    }

    @Test
    void openBlocksAreEmittedInnermostFirstWithTheirLeadingText() { // D2
        String doc = "p<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z";
        List<Block> blocks = WpBlocks.parse(doc);
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/b", null, "core/a");
        assertThat(blocks.get(0).innerHtml()).isEqualTo("x");
        assertThat(blocks.get(1).innerContent()).containsExactly("y", null, "z");
        assertThat(blocks.get(1).innerBlocks()).extracting(Block::name).containsExactly("core/c");
        assertThat(blocks.get(2).innerHtml()).isEqualTo("p");
        assertThat(blocks.get(3).innerContent()).containsExactly("x<!-- wp:b -->y<!-- wp:c /-->z");
        assertThat(blocks).extracting(Block::isRecoveredCopy).containsExactly(true, true, false, false);
        assertThat(WpBlocks.serialize(blocks)).isEqualTo(doc);
        assertThat(WpBlocks.serializeCanonical(blocks))
                .isEqualTo("x<!-- wp:b -->y<!-- wp:c /-->z<!-- /wp:b -->p"
                        + "<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z<!-- /wp:a -->");
    }

    @Test
    void theOuterBlockKeepsTextAfterItsLastClosedChild() { // D2
        List<Block> blocks = WpBlocks.parse("<!-- wp:a -->x<!-- wp:b -->y<!-- /wp:b -->z<!-- wp:c -->w");
        assertThat(blocks).extracting(Block::name).containsExactly(null, "core/c", "core/a");
        assertThat(blocks.get(0).innerHtml()).isEqualTo("z");
        assertThat(blocks.get(2).innerContent()).containsExactly("x", null, "z<!-- wp:c -->w");
        assertThat(blocks.get(2).innerBlocks().get(0).innerContent()).containsExactly("y");
    }

    @Test
    void aCloserWithNothingOpenEndsParsing() { // D1
        List<Block> blocks = WpBlocks.parse("x<!-- /wp:a -->y<!-- wp:b /-->z");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).innerHtml()).isEqualTo("x<!-- /wp:a -->y<!-- wp:b /-->z");
        List<Block> after = WpBlocks.parse("<!-- wp:a /-->x<!-- /wp:a -->y<!-- wp:b -->q<!-- /wp:b -->");
        assertThat(after).extracting(Block::name).containsExactly("core/a", null);
        assertThat(after.get(1).innerHtml()).isEqualTo("x<!-- /wp:a -->y<!-- wp:b -->q<!-- /wp:b -->");
        assertThat(WpBlocks.parse("x<!-- /wp:a {\"j\":1} -->y")).hasSize(1);
    }

    @Test
    void aNestedBlockClosedOnEmptyTextGetsAnEmptyChunk() {
        Block a = WpBlocks.parse("<!-- wp:a --><!-- wp:b --><!-- /wp:b --><!-- /wp:a -->")
                .get(0);
        assertThat(a.innerContent()).containsExactly((String) null);
        assertThat(a.innerBlocks().get(0).innerContent()).containsExactly("");
        assertThat(a.innerBlocks().get(0)).isEqualTo(Block.of("b"));
        assertThat(a.innerBlocks().get(0).hashCode()).isEqualTo(Block.of("b").hashCode());
    }

    @Test
    void aRecoveredCopyEqualsTheSameBlockWithoutTheFlag() {
        List<Block> blocks = WpBlocks.parse("<!-- wp:a -->x<!-- wp:b -->y");
        Block copy = blocks.get(1);
        Block plain = WpBlocks.parse("<!-- wp:b -->y").get(0);
        assertThat(copy.isRecoveredCopy()).isTrue();
        assertThat(plain.isRecoveredCopy()).isFalse();
        assertThat(copy).isEqualTo(plain);
        assertThat(copy.hashCode()).isEqualTo(plain.hashCode());
        assertThat(blocks.get(0)).isEqualTo(Block.freeform("x"));
        assertThat(copy.withAttributes(plain.attributes()).isRecoveredCopy()).isFalse();
    }

    @Test
    void editingTheOuterBlockOfARecoveredDocumentDoesNotDuplicateText() { // Review Focus 2
        List<Block> blocks = new ArrayList<>(WpBlocks.parse("p<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z"));
        blocks.set(
                3,
                blocks.get(3)
                        .withAttributes(JsonNodeFactory.instance.objectNode().put("k", 1)));
        assertThat(WpBlocks.serialize(blocks))
                .isEqualTo("p<!-- wp:a {\"k\":1} -->x<!-- wp:b -->y<!-- wp:c /-->z<!-- /wp:a -->");
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

    @Test
    void closeSequenceInsideAttributeStringStaysRoundTripSafe() {
        String doc = "<!-- wp:a {\"k\":\"} -->\"} -->x<!-- /wp:a -->";
        Block b = WpBlocks.parse(doc).get(0);
        assertThat(b.rawAttributes()).contains("{\"k\":\"}");
        assertThat(b.innerContent().get(0)).startsWith("\"} -->x");
        assertThat(b.rawOpener() + String.join("", b.innerContent()) + b.rawCloser())
                .isEqualTo(doc);
    }

    @Test
    void voidCloserInsideABlockIsANestedVoidBlock() { // D3
        Block a = WpBlocks.parse("<!-- wp:a -->x<!-- /wp:a /-->y").get(0);
        assertThat(a.name()).isEqualTo("core/a");
        assertThat(a.innerContent()).containsExactly("x", null, "y");
        assertThat(a.innerBlocks()).extracting(Block::name).containsExactly("core/a");
    }

    @Test
    void topLevelVoidCloserIsAVoidBlockWithAttributes() { // D3
        List<Block> blocks = WpBlocks.parse("<!-- /wp:a {\"k\":2} /-->");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).name()).isEqualTo("core/a");
        assertThat(blocks.get(0).attributes().get("k").intValue()).isEqualTo(2);
    }

    @Test
    void closerAttributesAreIgnored() { // D4
        List<Block> blocks = WpBlocks.parse("<!-- wp:a -->x<!-- /wp:a {\"x\":1} -->y");
        assertThat(blocks).extracting(Block::name).containsExactly("core/a", null);
        assertThat(blocks.get(0).innerContent()).containsExactly("x");
        assertThat(blocks.get(1).innerHtml()).isEqualTo("y");
        Block kept = WpBlocks.parse("<!-- wp:a {\"k\":1} --><!-- /wp:a {\"k\":2} -->")
                .get(0);
        assertThat(kept.attributes().get("k").intValue()).isEqualTo(1);
        assertThat(kept.innerContent()).isEmpty();
    }

    @Test
    void formFeedAndVerticalTabDelimitBlocks() { // D6
        assertThat(WpBlocks.parse("<!--\fwp:a\f/-->")).extracting(Block::name).containsExactly("core/a");
        assertThat(WpBlocks.parse("<!--\u000Bwp:a\u000B/-->"))
                .extracting(Block::name)
                .containsExactly("core/a");
        assertThat(WpBlocks.parse("<!-- wp:a -->\f<!-- /wp:a\u000B-->").get(0).innerContent())
                .containsExactly("\f");
        assertThat(WpBlocks.parse("<!--\u00A0 wp:a /-->").get(0).isFreeform()).isTrue();
        assertThat(WpBlocks.parse("<!-- wp:a/-->").get(0).isFreeform()).isTrue();
    }

    @Test
    void formFeedOrVerticalTabAfterAttributesMakesThemUndecodable() { // WordPress feeds the trailing space to
        // json_decode
        for (String doc :
                new String[] {"<!-- wp:a {\"x\":1}\f-->", "<!-- wp:a {\"x\":1}\u000B/-->", "<!-- /wp:a {\"x\":1}\f/-->"
                }) {
            Block b = WpBlocks.parse(doc).get(0);
            assertThat(b.name()).isEqualTo("core/a");
            assertThat(b.attributes()).isEmpty();
            assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
        }
        assertThat(WpBlocks.parse("<!-- wp:a {\"x\":1}\n-->")
                        .get(0)
                        .attributes()
                        .get("x")
                        .intValue())
                .isEqualTo(1);
        assertThat(WpBlocks.parse("<!-- wp:a\f{\"x\":1} -->")
                        .get(0)
                        .attributes()
                        .get("x")
                        .intValue())
                .isEqualTo(1);
    }

    @Test
    void closerWithoutWhitespaceBeforeCloseIsText() {
        Block a = WpBlocks.parse("<!-- wp:a -->x<!-- /wp:a/-->y").get(0);
        assertThat(a.innerContent()).containsExactly("x<!-- /wp:a/-->y");
    }

    @Test
    void invalidAttributeJsonIsReportedAsInvalid() { // D7: WordPress has null attributes for these
        Block broken = WpBlocks.parse("<!-- wp:a {\"x\":} /-->").get(0);
        assertThat(broken.attributesValid()).isFalse();
        assertThat(broken.attributes().isEmpty()).isTrue();
        assertThat(broken.rawAttributes()).contains("{\"x\":}");
        assertThat(WpBlocks.parse("<!-- wp:a {\"a\":1} {\"b\":2} /-->").get(0).attributesValid())
                .isFalse();
        assertThat(WpBlocks.parse("<!-- wp:a {\"x\":1} /-->").get(0).attributesValid())
                .isTrue();
        assertThat(WpBlocks.parse("<!-- wp:a /-->").get(0).attributesValid()).isTrue();
        assertThat(WpBlocks.parse("<!-- wp:a {} -->x<!-- /wp:a -->").get(0).attributesValid())
                .isTrue();
    }

    @Test
    void formFeedOrVerticalTabBeforeTheCloseMakesAttributesInvalid() { // WordPress json_decode fails on \f and \u000b
        assertThat(WpBlocks.parse("<!-- wp:a {\"x\":1}\f/-->").get(0).attributesValid())
                .isFalse();
        assertThat(WpBlocks.parse("<!-- wp:a {\"x\":1}\u000b-->").get(0).attributesValid())
                .isFalse();
        assertThat(WpBlocks.parse("<!-- wp:a {\"x\":1} /-->").get(0).attributesValid())
                .isTrue();
    }
}
