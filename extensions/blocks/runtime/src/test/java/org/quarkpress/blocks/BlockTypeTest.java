package org.quarkpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockTypeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void fromBlockJsonKeepsDeclaredKeysInOrder() throws Exception {
        BlockType type = BlockType.fromBlockJson(json("""
                {"apiVersion": 3, "name": "acme/notice", "title": "Notice", "category": "text",
                 "editorScript": "file:./index.js", "render": "file:./render.php", "unknown": 1,
                 "keywords": ["alert", "box"], "parent": ["core/group"], "textdomain": "acme",
                 "attributes": {"b": {"type": "string"}, "a": {"type": "number"}},
                 "providesContext": {"acme/id": "b"}, "usesContext": ["postId"],
                 "supports": {"html": false}, "styles": [{"name": "plain"}], "icon": "smiley"}
                """));

        assertThat(type.name()).isEqualTo("acme/notice");
        assertThat(type.title()).isEqualTo("Notice");
        assertThat(type.apiVersion()).isEqualTo(3);
        assertThat(type.category()).contains("text");
        assertThat(type.keywords()).containsExactly("alert", "box");
        assertThat(type.parent()).contains(List.of("core/group"));
        assertThat(type.ancestor()).isEmpty();
        assertThat(type.allowedBlocks()).isEmpty();
        assertThat(type.textdomain()).contains("acme");
        assertThat(type.icon()).map(JsonNode::asText).contains("smiley");
        assertThat(type.declaredAttributes().propertyStream().map(e -> e.getKey()))
                .containsExactly("b", "a");
        assertThat(type.providesContext().get("acme/id").asText()).isEqualTo("b");
        assertThat(type.usesContext()).containsExactly("postId");
        assertThat(type.supports().get("html").asBoolean()).isFalse();
        assertThat(type.styles()).hasSize(1);
        // keys outside the list are ignored: no scripts from block.json
        assertThat(type.editorScriptHandles()).isEmpty();
        assertThat(type.example()).isEmpty();
        assertThat(type.variations()).isEmpty();
        assertThat(type.blockHooks()).isEmpty();
        assertThat(type.selectors()).isEmpty();
    }

    @Test
    void defaultsAreWordPresss() {
        BlockType type = BlockType.builder("acme/plain").build();

        assertThat(type.title()).isEmpty();
        assertThat(type.description()).isEmpty();
        assertThat(type.apiVersion()).isEqualTo(1);
        assertThat(type.category()).isEmpty();
        assertThat(type.icon()).isEmpty();
        assertThat(type.keywords()).isEmpty();
        assertThat(type.declaredAttributes()).isEmpty();
        assertThat(type.scriptHandles()).isEmpty();
        assertThat(type.viewScriptModuleIds()).isEmpty();
    }

    @Test
    void nodesAreCopies() {
        BlockType type = BlockType.builder("acme/plain")
                .attributes(JSON.createObjectNode().put("x", 1))
                .build();

        ObjectNode attributes = type.declaredAttributes();
        attributes.put("y", 2);

        assertThat(type.declaredAttributes().has("y")).isFalse();
    }

    @Test
    void fromBlockJsonIgnoresWrongTypedValues() throws Exception {
        BlockType type = BlockType.fromBlockJson(json("""
                {"name": "acme/odd", "title": 5, "description": ["x"], "apiVersion": "3", "category": null,
                 "keywords": ["a", 1], "parent": "core/group", "attributes": [], "supports": "yes",
                 "styles": {}, "variations": {}, "usesContext": [true], "icon": null, "example": null}
                """));

        assertThat(type.title()).isEmpty();
        assertThat(type.description()).isEmpty();
        assertThat(type.apiVersion()).isEqualTo(1);
        assertThat(type.category()).isEmpty();
        assertThat(type.keywords()).isEmpty();
        assertThat(type.parent()).isEmpty();
        assertThat(type.declaredAttributes()).isEmpty();
        assertThat(type.supports()).isEmpty();
        assertThat(type.styles()).isEmpty();
        assertThat(type.variations()).isEmpty();
        assertThat(type.usesContext()).isEmpty();
        assertThat(type.icon()).isEmpty();
        assertThat(type.example()).isEmpty();
    }

    @Test
    void aRenderContextCopiesItsAttributes() {
        ObjectNode attributes = JSON.createObjectNode().put("a", 1);
        BlockRenderContext context = new BlockRenderContext("acme/x", attributes, java.util.OptionalLong.empty());

        attributes.put("b", 2);
        context.attributes().put("c", 3);

        assertThat(context.attributes().propertyStream().map(e -> e.getKey())).containsExactly("a");
        assertThatThrownBy(() -> new BlockRenderContext("acme/x", null, java.util.OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void namesAreNamespacedLowerCase() {
        assertThat(BlockType.builder("acme/notice").build().name()).isEqualTo("acme/notice");
        assertThat(BlockType.builder("a1-b/c-2").build().name()).isEqualTo("a1-b/c-2");
        for (String name :
                List.of("Acme/notice", "notice", "acme/", "/notice", "a/b/c", "acme/_x", "1acme/x", "acme/-x", "")) {
            assertThatThrownBy(() -> BlockType.builder(name))
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("QuarkPress: a block type name is <namespace>/<name> in lower-case letters, digits"
                            + " and dashes: " + name);
        }
    }
}
