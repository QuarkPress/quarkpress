package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Writes blocks back to markup. {@link #append} writes a parsed, unmodified block from its raw delimiter text, so
 * it comes back byte for byte; any other block gets canonical delimiters. {@link #appendCanonical} ignores all raw
 * text and writes what WordPress's {@code serialize_block} writes.
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
            appendContent(out, block, false);
            if (block.rawCloser() != null) {
                out.append(block.rawCloser());
            }
            return;
        }
        StringBuilder content = new StringBuilder();
        appendContent(content, block, false);
        String attributes = block.rawAttributes().orElseGet(() -> canonicalAttributes(block));
        appendDelimited(out, block.name(), attributes, content);
    }

    static void appendCanonical(StringBuilder out, Block block) {
        if (block.isFreeform()) {
            out.append(block.innerHtml());
            return;
        }
        StringBuilder content = new StringBuilder();
        appendContent(content, block, true);
        appendDelimited(out, block.name(), canonicalAttributes(block), content);
    }

    /** Null when the block has no attributes, as WordPress then writes none. */
    private static String canonicalAttributes(Block block) {
        ObjectNode attributes = block.attributesView();
        return attributes.isEmpty() ? null : WordPressJson.write(attributes);
    }

    /** The void form when there is no content, else opener, content and closer; {@code core/} is dropped. */
    private static void appendDelimited(StringBuilder out, String name, String attributes, CharSequence content) {
        String shortName = BlockNames.shorten(name);
        out.append("<!-- wp:").append(shortName).append(' ');
        if (attributes != null) {
            out.append(attributes).append(' ');
        }
        if (content.isEmpty()) {
            out.append("/-->");
            return;
        }
        out.append("-->").append(content).append("<!-- /wp:").append(shortName).append(" -->");
    }

    private static void appendContent(StringBuilder out, Block block, boolean canonical) {
        List<Block> inner = block.innerBlocks();
        int next = 0;
        for (String chunk : block.innerContent()) {
            if (chunk != null) {
                out.append(chunk);
                continue;
            }
            Block child = inner.get(next++);
            if (canonical) {
                appendCanonical(out, child);
            } else {
                append(out, child);
            }
        }
    }
}
