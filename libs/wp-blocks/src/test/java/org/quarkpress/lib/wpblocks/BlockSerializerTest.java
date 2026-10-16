package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockSerializerTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "plain <b>classic</b> content\n",
                "<!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph -->",
                "<!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph -->\n",
                "<!--   wp:acme/hero   {\"a\" : 1 ,\"b\":[ ]}\t/-->",
                "<!-- wp:core/html -->\n<h1>x</h1>\n<!-- /wp:core/html -->",
                "<!--\twp:a\n-->x<!--  /wp:a\r\n-->",
                "<!-- wp:a --><!-- /wp:a -->",
                "<!-- wp:a -->x<!-- /wp:a /-->y",
                "<!-- /wp:a {\"k\":2} /-->",
                "<!-- wp:a -->x<!-- /wp:a {\"x\":1} -->y",
                "<!--\fwp:a\f/-->",
                "<!-- wp:a -->\f<!-- /wp:a\u000B-->",
                "<!-- wp:a -->\n\t<!-- wp:b {\"x\":\"}\"} -->y<!-- /wp:b -->\n<!-- /wp:a -->\n\n<!-- wp:c /-->",
                "<!-- wp:Paragraph -->not a block<!-- /wp:paragraph -->",
                "<!-- wp:group --><p>unclosed",
                "stray closer <!-- /wp:x --> here",
                "<!-- wp:a {\"k\":} /-->",
                "<!-<!-- wp:a /-->",
                "<!-- wp:a --><!-- wp:b -->x<!-- /wp:a -->y<!-- /wp:b -->",
                "x<!-- /wp:a -->y<!-- wp:b /-->z",
                "<!-- wp:a /-->x<!-- /wp:a -->y<!-- wp:b -->q<!-- /wp:b -->",
                "<!-- wp:a -->x<!-- wp:b -->y",
                "p<!-- wp:a -->x<!-- wp:b -->y<!-- wp:c /-->z",
                "<!-- wp:a -->x<!-- wp:b -->y<!-- /wp:b -->z<!-- wp:c -->w",
                "<!-- wp:a --><!-- wp:b --><!-- /wp:b --><!-- /wp:a -->"
            })
    void serializeOfParseIsIdentity(String doc) {
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
    }

    @Test
    void apiBlockWithoutContentIsVoidForm() {
        assertThat(WpBlocks.serialize(Block.of("spacer"))).isEqualTo("<!-- wp:spacer /-->");
        ObjectNode attrs = JsonNodeFactory.instance.objectNode().put("height", 10);
        assertThat(WpBlocks.serialize(Block.of("spacer", attrs))).isEqualTo("<!-- wp:spacer {\"height\":10} /-->");
    }

    @Test
    void apiBlockWithContentIsOpenerContentCloser() {
        Block p = Block.builder("paragraph").text("\n<p>Hi</p>\n").build();
        assertThat(WpBlocks.serialize(p)).isEqualTo("<!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph -->");
        Block g = Block.builder("group")
                .text("<div>")
                .inner(p)
                .inner(Block.of("a"))
                .text("</div>")
                .build();
        assertThat(WpBlocks.serialize(g))
                .isEqualTo(
                        "<!-- wp:group --><div><!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph --><!-- wp:a /--></div><!-- /wp:group -->");
    }

    @Test
    void coreNamespaceIsDroppedOthersKept() {
        assertThat(WpBlocks.serialize(Block.of("core/x"))).isEqualTo("<!-- wp:x /-->");
        assertThat(WpBlocks.serialize(Block.of("acme/x"))).isEqualTo("<!-- wp:acme/x /-->");
    }

    @Test
    void freeformIsWrittenAsIs() {
        assertThat(WpBlocks.serialize(Block.freeform("<p>x</p> -- <!-- c -->"))).isEqualTo("<p>x</p> -- <!-- c -->");
    }

    @Test
    void regeneratedAttributesAreCompactAndEscaped() {
        ObjectNode attrs = JsonNodeFactory.instance.objectNode().put("t", "a--b<c>");
        assertThat(WpBlocks.serialize(Block.of("x", attrs)))
                .isEqualTo("<!-- wp:x {\"t\":\"a\\u002d\\u002db\\u003cc\\u003e\"} /-->");
    }

    @Test
    void serializeOfListConcatenates() {
        List<Block> blocks = List.of(Block.freeform("a"), Block.of("x"), Block.freeform("b"));
        assertThat(WpBlocks.serialize(blocks)).isEqualTo("a<!-- wp:x /-->b");
    }

    @Test
    void parseOfSerializeIsStructurallyEqual() {
        ObjectNode attrs =
                JsonNodeFactory.instance.objectNode().put("t", "q\"--<>&").put("n", 2);
        Block tree = Block.builder("acme/outer")
                .attributes(attrs)
                .text("<section>")
                .inner(Block.builder("paragraph").text("<p>x</p>").build())
                .text("\n")
                .inner(Block.of("spacer", JsonNodeFactory.instance.objectNode().put("h", 1)))
                .text("</section>")
                .build();
        List<Block> back = WpBlocks.parse(WpBlocks.serialize(tree));
        assertThat(back).containsExactly(tree);
    }

    private static void assertCanonical(String in, String wordpress) {
        assertThat(WpBlocks.serializeCanonical(WpBlocks.parse(in))).as(in).isEqualTo(wordpress);
    }

    @Test
    void canonicalSerializationMatchesWordPress() { // D14, D15, D16, D17
        assertCanonical(
                "<!-- wp:core/paragraph -->x<!-- /wp:core/paragraph -->",
                "<!-- wp:paragraph -->x<!-- /wp:paragraph -->");
        assertCanonical("<!--\twp:a\t{\"x\":1}\t/-->", "<!-- wp:a {\"x\":1} /-->");
        assertCanonical("<!--\n wp:a \n {\"x\":1}\n\t/-->", "<!-- wp:a {\"x\":1} /-->");
        assertCanonical("<!-- wp:a --><!-- /wp:a -->", "<!-- wp:a /-->");
        assertCanonical("<!-- wp:a {} /-->", "<!-- wp:a /-->");
        assertCanonical("<!-- wp:a {\"x\":} /-->", "<!-- wp:a /-->");
        assertCanonical("<!-- wp:a {\"a\":1,\"b\":2,\"a\":3} /-->", "<!-- wp:a {\"a\":3,\"b\":2} /-->");
        assertCanonical("<!-- wp:a --><!-- wp:b /--><!-- /wp:a -->", "<!-- wp:a --><!-- wp:b /--><!-- /wp:a -->");
        assertCanonical(
                "<!-- wp:a {\"f\":1.0,\"e\":1e2,\"g\":1.5e300,\"z\":-0.0,\"h\":0.1,\"t\":1e20,\"i\":-0} /-->",
                "<!-- wp:a {\"f\":1,\"e\":100,\"g\":1.5e+300,\"z\":-0,\"h\":0.1,\"t\":1.0e+20,\"i\":0} /-->");
        assertCanonical(
                "<!-- wp:a {\"s\":\"a\\\\b \\\" -- --- < > & / é \u2028 \\u0001 \\u001f \\t \\/\"} /-->",
                "<!-- wp:a {\"s\":\"a\\u005cb \\u0022 \\u002d\\u002d \\u002d\\u002d- \\u003c \\u003e \\u0026 / é"
                        + " \\u2028 \\u0001 \\u001f \\t /\"} /-->");
        assertCanonical(
                "<!-- wp:math {\"latex\":\"\\\\frac{a}{b}\"} /-->",
                "<!-- wp:math {\"latex\":\"\\u005cfrac{a}{b}\"} /-->");
        assertCanonical("a<!-- wp:p /-->b", "a<!-- wp:p /-->b");
    }

    @Test
    void serializeKeepsRawTextWhileCanonicalDoesNot() { // D17
        String doc = "<!-- wp:core/a  {\"x\" : 1}  /-->";
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
        assertThat(WpBlocks.serializeCanonical(WpBlocks.parse(doc))).isEqualTo("<!-- wp:a {\"x\":1} /-->");
    }
}
