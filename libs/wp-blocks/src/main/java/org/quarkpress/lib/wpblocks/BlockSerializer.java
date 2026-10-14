package org.quarkpress.lib.wpblocks;

import java.util.List;

/**
 * Writes blocks back to Gutenberg markup. A parsed, unmodified block is written from its raw delimiter
 * text, so it comes back byte for byte (original whitespace, a written {@code core/} prefix, a missing
 * closer at end of input). Anything else is written in canonical form.
 */
final class BlockSerializer {

    private BlockSerializer() {}

    static void append(StringBuilder out, Block block) {
        if (block.isFreeform()) {
            out.append(block.innerHtml());
            return;
        }
        if (block.rawOpener() != null) {
            out.append(block.rawOpener());
            appendContent(out, block);
            if (block.rawCloser() != null) {
                out.append(block.rawCloser());
            }
            return;
        }
        String name = BlockNames.shorten(block.name());
        String attrs = block.rawAttributes().orElseGet(() -> {
            var node = block.attributes();
            return node.isEmpty() ? null : AttributeJson.write(node);
        });
        out.append("<!-- wp:").append(name);
        if (attrs != null) {
            out.append(' ').append(attrs);
        }
        boolean voidForm = block.innerBlocks().isEmpty() && block.innerHtml().isEmpty() && !block.explicitClose();
        if (voidForm) {
            out.append(" /-->");
            return;
        }
        out.append(" -->");
        appendContent(out, block);
        out.append("<!-- /wp:").append(name).append(" -->");
    }

    private static void appendContent(StringBuilder out, Block block) {
        List<Block> inner = block.innerBlocks();
        int next = 0;
        for (String chunk : block.innerContent()) {
            if (chunk == null) {
                append(out, inner.get(next++));
            } else {
                out.append(chunk);
            }
        }
    }
}
