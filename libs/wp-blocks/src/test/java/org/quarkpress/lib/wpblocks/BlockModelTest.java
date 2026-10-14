package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockModelTest {

    private static ObjectNode attrs(String key, int value) {
        return JsonNodeFactory.instance.objectNode().put(key, value);
    }

    @Test
    void freeformBlockHasNullNameAndSingleChunk() {
        Block b = Block.freeform("<p>x</p>");
        assertThat(b.isFreeform()).isTrue();
        assertThat(b.name()).isNull();
        assertThat(b.attributes().isEmpty()).isTrue();
        assertThat(b.rawAttributes()).isEmpty();
        assertThat(b.innerBlocks()).isEmpty();
        assertThat(b.innerHtml()).isEqualTo("<p>x</p>");
        assertThat(b.innerContent()).containsExactly("<p>x</p>");
    }

    @Test
    void ofQualifiesBareNamesAndRejectsInvalidOnes() {
        assertThat(Block.of("paragraph").name()).isEqualTo("core/paragraph");
        assertThat(Block.of("acme/hero", attrs("a", 1)).attributes().get("a").intValue())
                .isEqualTo(1);
        assertThatThrownBy(() -> Block.of("Bad Name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Block.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderKeepsChunkOrderMergesTextAndSkipsEmptyText() {
        Block inner = Block.of("paragraph");
        Block b = Block.builder("group")
                .text("<div>")
                .text("\n")
                .inner(inner)
                .text("")
                .inner(inner)
                .text("</div>")
                .build();
        assertThat(b.innerBlocks()).containsExactly(inner, inner);
        assertThat(b.innerContent()).containsExactly("<div>\n", null, null, "</div>");
        assertThat(b.innerHtml()).isEqualTo("<div>\n</div>");
        assertThat(b.rawAttributes()).isEmpty();
    }

    @Test
    void voidShapeHasEmptyHtmlAndNoChunks() {
        Block b = Block.of("spacer");
        assertThat(b.innerHtml()).isEmpty();
        assertThat(b.innerContent()).isEmpty();
        assertThat(b.innerBlocks()).isEmpty();
    }

    @Test
    void constructorEnforcesInvariants() {
        ObjectNode none = JsonNodeFactory.instance.objectNode();
        // null count must equal inner block count
        assertThatThrownBy(() -> new Block("core/a", none, null, List.of(), Arrays.asList("x", null)))
                .isInstanceOf(IllegalArgumentException.class);
        // freeform must have empty attributes and no inner blocks
        assertThatThrownBy(() -> new Block(null, attrs("a", 1), null, List.of(), List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block(null, none, null, List.of(Block.of("a")), Arrays.asList("x", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attributesAreDefensivelyCopied() {
        ObjectNode a = attrs("a", 1);
        Block b = Block.of("x", a);
        a.put("a", 2);
        assertThat(b.attributes().get("a").intValue()).isEqualTo(1);
        b.attributes().put("a", 3);
        assertThat(b.attributes().get("a").intValue()).isEqualTo(1);
    }

    @Test
    void collectionsAreUnmodifiable() {
        Block b = Block.builder("g").inner(Block.of("a")).build();
        assertThatThrownBy(() -> b.innerBlocks().add(Block.of("b"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> b.innerContent().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalityIgnoresRawTextButNotValues() {
        ObjectNode a = attrs("a", 1);
        Block parsedLike = new Block(
                "core/x", a, "{\"a\": 1}", "<!-- wp:x {\"a\": 1} -->", "<!-- /wp:x -->", true, List.of(), List.of());
        Block apiLike = Block.of("x", a);
        assertThat(parsedLike).isEqualTo(apiLike);
        assertThat(parsedLike.hashCode()).isEqualTo(apiLike.hashCode());
        assertThat(Block.of("x", attrs("a", 2))).isNotEqualTo(apiLike);
        assertThat(Block.of("y", a)).isNotEqualTo(apiLike);
    }

    @Test
    void apiBuiltNumbersRoundTripAsJsonValues() {
        ObjectNode a = JsonNodeFactory.instance.objectNode();
        a.put("l", 1L);
        a.put("big", 5_000_000_000L);
        a.put("f", 0.5f);
        a.put("g", 0.1f);
        a.put("bd", new BigDecimal("1.10"));
        a.put("s", (short) 3);
        a.put("bi", BigInteger.valueOf(7));
        ArrayNode arr = a.putArray("arr");
        arr.add(2L).add(0.25f).add(new BigDecimal("2.50")).add((short) 4);
        ObjectNode nested = arr.addObject();
        nested.put("n", 9L).put("m", 0.1f);
        a.putObject("o")
                .put("x", new BigDecimal("3.0"))
                .putArray("y")
                .add((short) 1)
                .add(1L);
        Block block = Block.builder("acme/n").attributes(a).text("<p>x</p>").build();

        Block back = WpBlocks.parse(WpBlocks.serialize(List.of(block))).get(0);

        assertThat(back).isEqualTo(block);
        assertThat(back.hashCode()).isEqualTo(block.hashCode());
        assertThat(block.attributes().get("g").doubleValue()).isEqualTo(0.1);
    }

    @Test
    void attributesRejectNonJsonValueNodes() {
        ObjectNode pojo = JsonNodeFactory.instance.objectNode();
        pojo.putArray("list").add(1).addPOJO(new Object());
        assertThatThrownBy(() -> Block.of("x", pojo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attributes must contain only JSON values: $.list[1]");
        assertThat(pojo.get("list").get(1)).isInstanceOf(POJONode.class);

        ObjectNode binary = JsonNodeFactory.instance.objectNode();
        binary.putArray("list").add(new byte[] {1, 2});
        assertThat(binary.get("list").get(0)).isInstanceOf(BinaryNode.class);
        assertThatThrownBy(() -> Block.builder("x").attributes(binary).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attributes must contain only JSON values: $.list[0]");
    }
}
