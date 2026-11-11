package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeProvider;

/**
 * The block type {@code qp-it/notice} of the REST parity comparison and its renderer: title "Notice", category
 * {@code text}, one string attribute {@code message} (default empty), rendered as {@code <p class="qp-notice">} with
 * the message escaped as HTML. The WordPress side of the comparison registers the same type when a scenario turns on
 * its must-use plugin's {@code qp_parity_notice_block} switch through an option setup step.
 */
@ApplicationScoped
public class ItNoticeBlock implements BlockTypeProvider, BlockRenderer {

    static final String NAME = "qp-it/notice";

    @Override
    public List<BlockType> blockTypes() {
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.putObject("message").put("type", "string").put("default", "");
        return List.of(BlockType.builder(NAME)
                .title("Notice")
                .category("text")
                .attributes(attributes)
                .build());
    }

    @Override
    public String blockName() {
        return NAME;
    }

    @Override
    public String render(BlockRenderContext context) {
        JsonNode message = context.attributes().get("message");
        return "<p class=\"qp-notice\">" + escape(message == null || message.isNull() ? "" : message.asText()) + "</p>";
    }

    /** {@code &}, {@code <}, {@code >}, {@code "} and {@code '} as HTML entities. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
