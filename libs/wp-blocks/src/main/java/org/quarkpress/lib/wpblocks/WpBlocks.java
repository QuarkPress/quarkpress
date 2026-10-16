package org.quarkpress.lib.wpblocks;

import java.util.List;
import java.util.Objects;

/** Entry point: parse Gutenberg block markup into {@link Block}s and serialize them back. */
public final class WpBlocks {

    private WpBlocks() {}

    /**
     * Parses with {@link ParseOptions#DEFAULTS}. Throws {@link BlockParseException} only when a {@link ParseOptions}
     * limit is exceeded; malformed markup is otherwise read as WordPress reads it.
     */
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

    /**
     * Serializes one block as {@link #serialize(List)} serializes each block of a list. The block is written even
     * when it is a {@linkplain Block#isRecoveredCopy() recovered copy}; only {@link #serialize(List)} skips copies.
     */
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

    private static final String BLOCK_COMMENT = "<!-- wp:";

    /**
     * WordPress's {@code has_blocks}: true when the text contains {@code "<!-- wp:"}. A cheap, loose test: a comment
     * that only resembles a delimiter counts, and a delimiter with a tab or a newline after {@code <!--} does not.
     * WordPress uses it to decide whether a post is rendered as blocks. Parse the content for an exact answer.
     */
    public static boolean hasBlocks(String content) {
        Objects.requireNonNull(content, "content");
        return content.contains(BLOCK_COMMENT);
    }

    /**
     * WordPress's {@code has_block}: false when {@link #hasBlocks} is; otherwise whether the text contains {@code
     * "<!-- wp:"} followed by the qualified name ({@code core/} added when {@code name} has no slash) and a space,
     * or, for a core block, by its short name and a space. A substring test like WordPress's; names are compared
     * exactly.
     */
    public static boolean hasBlock(String content, String name) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(name, "name");
        if (!hasBlocks(content)) {
            return false;
        }
        String qualified = name.indexOf('/') < 0 ? BlockNames.CORE + name : name;
        if (content.contains(BLOCK_COMMENT + qualified + " ")) {
            return true;
        }
        String shortName = stripCoreNamespace(qualified);
        return !shortName.equals(qualified) && content.contains(BLOCK_COMMENT + shortName + " ");
    }

    /** WordPress's {@code strip_core_block_namespace}: removes a leading {@code core/}; other names are unchanged. */
    public static String stripCoreNamespace(String name) {
        Objects.requireNonNull(name, "name");
        return BlockNames.shorten(name);
    }
}
