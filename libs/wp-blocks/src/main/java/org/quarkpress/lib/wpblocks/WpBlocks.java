package org.quarkpress.lib.wpblocks;

import java.util.List;
import java.util.Objects;

/** Entry point: parse Gutenberg block markup into {@link Block}s and serialize them back. */
public final class WpBlocks {

    private WpBlocks() {}

    /** Parses with {@link ParseOptions#DEFAULTS}. Never throws for malformed markup. */
    public static List<Block> parse(String content) {
        return parse(content, ParseOptions.DEFAULTS);
    }

    /**
     * Parses block markup. Anything that is not a valid delimiter is text; text outside blocks becomes
     * freeform blocks. Throws {@link BlockParseException} only when a limit in {@code options} is exceeded.
     */
    public static List<Block> parse(String content, ParseOptions options) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        return BlockParser.parse(content, options);
    }

    /**
     * Serializes blocks in order. Parsed, unmodified blocks come back byte for byte, so {@code
     * serialize(parse(x)).equals(x)} for every {@code x}. {@linkplain Block#isRecoveredCopy() Recovered copies}
     * are skipped: their text is already inside the outermost block left open at the end of the document.
     *
     * <p>{@code parse(serialize(blocks))} equals API-built {@code blocks} only when no two top-level freeform
     * blocks are adjacent (they re-parse as one), no freeform block is empty (it disappears), and no text contains
     * block delimiters (it re-parses as delimiters); see {@link Block}.
     *
     * <p>Serialization recurses once per nesting level. Parsed trees are bounded by {@link ParseOptions#maxDepth()}
     * (at most 1024); a very deep API-built tree can exhaust the stack.
     */
    public static String serialize(List<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            if (!Objects.requireNonNull(b, "block").isRecoveredCopy()) {
                BlockSerializer.append(out, b);
            }
        }
        return out.toString();
    }

    public static String serialize(Block block) {
        StringBuilder out = new StringBuilder();
        BlockSerializer.append(out, Objects.requireNonNull(block, "block"));
        return out.toString();
    }

    /**
     * Serializes blocks exactly as WordPress's {@code serialize_blocks} does: every delimiter is regenerated from
     * the block's name and attributes, ignoring raw source text; {@code core/} is dropped; a block with no content
     * gets the void form; attributes are written as WordPress writes them.
     */
    public static String serializeCanonical(List<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            BlockSerializer.appendCanonical(out, Objects.requireNonNull(b, "block"));
        }
        return out.toString();
    }

    /**
     * True if the content contains at least one valid block opener or void delimiter. A closer alone,
     * or a comment that only resembles a delimiter, does not count. Does not build a tree.
     */
    public static boolean hasBlocks(String content) {
        Objects.requireNonNull(content, "content");
        AttributeEnds ends = new AttributeEnds(content);
        int from = 0;
        while (true) {
            int idx = content.indexOf(DelimiterScanner.COMMENT_OPEN, from);
            if (idx < 0) {
                return false;
            }
            DelimiterScanner.Delimiter d = DelimiterScanner.tryRead(content, idx, ends);
            if (d != null && d.kind() != DelimiterScanner.Kind.CLOSER) {
                return true;
            }
            from = idx + 1;
        }
    }
}
