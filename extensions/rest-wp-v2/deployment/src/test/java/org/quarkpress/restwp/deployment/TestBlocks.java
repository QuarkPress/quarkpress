package org.quarkpress.restwp.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;

/**
 * The block types of the block types tests: {@code acme/notice} with a title only (WordPress's defaults),
 * {@code acme/dyn} rendered by {@link Renderer}, {@code acme/full} with every property set, and {@code other/plain} in
 * another namespace.
 */
@ApplicationScoped
public class TestBlocks implements BlockTypeProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    static <T> T json(String text, Class<T> type) {
        try {
            return type.cast(JSON.readTree(text));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public List<BlockType> blockTypes() {
        return List.of(
                BlockType.builder("acme/notice").title("Notice").build(),
                BlockType.builder("acme/dyn").title("Dynamic").build(),
                BlockType.builder("acme/full")
                        .apiVersion(3)
                        .title("Full")
                        .description("Every property.")
                        .icon(json("\"smiley\"", com.fasterxml.jackson.databind.JsonNode.class))
                        .category("widgets")
                        .keywords(List.of("a", "b"))
                        .parent(List.of("core/group"))
                        .ancestor(List.of("core/columns"))
                        .allowedBlocks(List.of("core/paragraph"))
                        .attributes(json(
                                "{\"content\":{\"type\":\"string\",\"default\":\"\"},"
                                        + "\"opts\":{\"type\":\"object\",\"default\":{}}}",
                                ObjectNode.class))
                        .providesContext(json("{\"acme/id\":\"content\"}", ObjectNode.class))
                        .usesContext(List.of("postId"))
                        .selectors(json("{\"root\":\".acme\"}", ObjectNode.class))
                        .supports(json("{\"anchor\":true,\"html\":false}", ObjectNode.class))
                        .styles(json(
                                "[{\"name\":\"plain\",\"label\":\"Plain\",\"is_default\":true},{}]", ArrayNode.class))
                        .textdomain("acme")
                        .example(json("{\"attributes\":{\"content\":\"x\"}}", ObjectNode.class))
                        .variations(json("[{\"name\":\"v\",\"title\":\"V\"}]", ArrayNode.class))
                        .blockHooks(json("{\"core/paragraph\":\"after\"}", ObjectNode.class))
                        .editorScriptHandles(List.of("acme-editor", "acme-editor-2"))
                        .scriptHandles(List.of("acme"))
                        .viewScriptModuleIds(List.of("@acme/view"))
                        .editorStyleHandles(List.of("acme-editor-style"))
                        .styleHandles(List.of("acme-style"))
                        .viewStyleHandles(List.of("acme-view"))
                        .build(),
                BlockType.builder("other/plain").title("Plain").build());
    }

    /** Renders {@code acme/dyn}, which makes it a dynamic block type. */
    @ApplicationScoped
    public static class Renderer implements BlockRenderer {

        @Override
        public String blockName() {
            return "acme/dyn";
        }

        @Override
        public String render(BlockRenderContext context) {
            return "<p>dyn</p>";
        }
    }
}
