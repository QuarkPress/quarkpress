package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
}
