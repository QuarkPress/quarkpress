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

    /** Serializes blocks in order. Parsed, unmodified blocks come back byte for byte. */
    public static String serialize(List<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            BlockSerializer.append(out, Objects.requireNonNull(b, "block"));
        }
        return out.toString();
    }

    public static String serialize(Block block) {
        StringBuilder out = new StringBuilder();
        BlockSerializer.append(out, Objects.requireNonNull(block, "block"));
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
