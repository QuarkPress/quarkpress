package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockApiTest {

    @Test
    void withAttributesClearsRawAndRegeneratesOnSerialize() {
        Block parsed =
                WpBlocks.parse("<!-- wp:a {\"k\" : 1} -->x<!-- /wp:a -->").get(0);
        assertThat(parsed.rawAttributes()).contains("{\"k\" : 1}");
        ObjectNode attrs = JsonNodeFactory.instance.objectNode().put("k", 2);
        Block changed = parsed.withAttributes(attrs);
        assertThat(changed.rawAttributes()).isEmpty();
        assertThat(changed.attributes().get("k").intValue()).isEqualTo(2);
        assertThat(changed.innerContent()).isEqualTo(parsed.innerContent());
        assertThat(WpBlocks.serialize(changed)).isEqualTo("<!-- wp:a {\"k\":2} -->x<!-- /wp:a -->");
        // the original is untouched
        assertThat(WpBlocks.serialize(parsed)).isEqualTo("<!-- wp:a {\"k\" : 1} -->x<!-- /wp:a -->");
    }

    @Test
    void withAttributesToEmptyDropsTheAttributeSegment() {
        Block parsed = WpBlocks.parse("<!-- wp:a {\"k\":1} /-->").get(0);
        assertThat(WpBlocks.serialize(parsed.withAttributes(JsonNodeFactory.instance.objectNode())))
                .isEqualTo("<!-- wp:a /-->");
    }

    @Test
    void withInnerBlocksRebuildsContentAndKeepsRawAttributes() {
        Block parsed = WpBlocks.parse("<!-- wp:g {\"k\" : 1} --><div><!-- wp:a /--></div><!-- /wp:g -->")
                .get(0);
        Block changed = parsed.withInnerBlocks(List.of(Block.of("b"), Block.of("c")));
        assertThat(changed.rawAttributes()).contains("{\"k\" : 1}");
        assertThat(changed.innerBlocks()).extracting(Block::name).containsExactly("core/b", "core/c");
        assertThat(changed.innerContent()).containsExactly(null, null);
        assertThat(changed.innerHtml()).isEmpty();
        assertThat(WpBlocks.serialize(changed))
                .isEqualTo("<!-- wp:g {\"k\" : 1} --><!-- wp:b /--><!-- wp:c /--><!-- /wp:g -->");
    }

    @Test
    void withInnerContentKeepsText() {
        Block parsed = WpBlocks.parse("<!-- wp:g {\"k\" : 1} --><div><!-- wp:a /--><p>x</p></div><!-- /wp:g -->")
                .get(0);
        // the block a is replaced by two blocks, the text around it kept
        Block changed = parsed.withInnerContent(
                List.of(Block.of("b"), Block.of("c")), Arrays.asList("<div>", null, null, "<p>x</p></div>"));
        assertThat(changed.rawAttributes()).contains("{\"k\" : 1}");
        assertThat(changed.innerHtml()).isEqualTo("<div><p>x</p></div>");
        assertThat(WpBlocks.serializeCanonical(List.of(changed)))
                .isEqualTo("<!-- wp:g {\"k\":1} --><div><!-- wp:b /--><!-- wp:c /--><p>x</p></div><!-- /wp:g -->");
        assertThat(WpBlocks.serialize(changed))
                .isEqualTo("<!-- wp:g {\"k\" : 1} --><div><!-- wp:b /--><!-- wp:c /--><p>x</p></div><!-- /wp:g -->");
    }

    @Test
    void withInnerContentNeedsOnePositionPerBlock() {
        Block parsed = WpBlocks.parse("<!-- wp:g --><div><!-- wp:a /--></div><!-- /wp:g -->")
                .get(0);
        assertThatThrownBy(() -> parsed.withInnerContent(List.of(Block.of("b")), List.of("<div></div>")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Block.freeform("x").withInnerContent(List.of(), List.of("y")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withInnerBlocksEmptyWritesTheVoidFormAsWordPressDoes() { // D16
        Block parsed =
                WpBlocks.parse("<!-- wp:g --><!-- wp:a /--><!-- /wp:g -->").get(0);
        assertThat(WpBlocks.serialize(parsed.withInnerBlocks(List.of()))).isEqualTo("<!-- wp:g /-->");
    }

    @Test
    void regeneratedAttributesUseWordPressJson() { // D14, D15
        ObjectNode attrs = JsonNodeFactory.instance
                .objectNode()
                .put("f", 1.0)
                .put("t", 1e20)
                .put("latex", "\\frac");
        assertThat(WpBlocks.serialize(Block.of("math", attrs)))
                .isEqualTo("<!-- wp:math {\"f\":1,\"t\":1.0e+20,\"latex\":\"\\u005cfrac\"} /-->");
    }

    @Test
    void siblingsOfEditedBlocksStayByteIdentical() {
        String doc = "<!-- wp:a {\"x\" :  1} /-->\n<!-- wp:b {\"y\" :  2} /-->";
        List<Block> blocks = WpBlocks.parse(doc);
        Block edited = blocks.get(2)
                .withAttributes(JsonNodeFactory.instance.objectNode().put("y", 3));
        String out = WpBlocks.serialize(List.of(blocks.get(0), blocks.get(1), edited));
        assertThat(out).isEqualTo("<!-- wp:a {\"x\" :  1} /-->\n<!-- wp:b {\"y\":3} /-->");
    }

    @Test
    void mutationHelpersRejectFreeform() {
        Block f = Block.freeform("x");
        assertThatThrownBy(() ->
                        f.withAttributes(JsonNodeFactory.instance.objectNode().put("a", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.withInnerBlocks(List.of(Block.of("a"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attributeValidityFollowsTheMutation() {
        Block broken = WpBlocks.parse("<!-- wp:g {\"x\":} --><!-- wp:a /--><!-- /wp:g -->")
                .get(0);
        assertThat(broken.withInnerBlocks(List.of()).attributesValid()).isFalse();
        assertThat(broken.withAttributes(JsonNodeFactory.instance.objectNode()).attributesValid())
                .isTrue();
        assertThat(Block.of("a").attributesValid()).isTrue();
    }
}
