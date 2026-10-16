package org.quarkpress.lib.wpblocks;

/**
 * Parser limits. Start from {@link #DEFAULTS} and derive with the {@code with*} methods; there is
 * deliberately no public constructor so that new options can be added compatibly.
 */
public final class ParseOptions {

    /** Hard upper bound for {@link #maxDepth()}; the serializer is recursive and must stay stack-safe. */
    public static final int MAX_DEPTH_LIMIT = 1024;

    /** No WordPress limit corresponds to these; they bound memory and stack for hostile input only. */
    public static final ParseOptions DEFAULTS = new ParseOptions(MAX_DEPTH_LIMIT, 1_000_000);

    private final int maxDepth;
    private final int maxBlocks;

    private ParseOptions(int maxDepth, int maxBlocks) {
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH_LIMIT);
        }
        if (maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be >= 1");
        }
        this.maxDepth = maxDepth;
        this.maxBlocks = maxBlocks;
    }

    /** Maximum nesting depth of blocks (1..{@link #MAX_DEPTH_LIMIT}). */
    public int maxDepth() {
        return maxDepth;
    }

    /** Maximum number of blocks, freeform blocks included, in one document. */
    public int maxBlocks() {
        return maxBlocks;
    }

    public ParseOptions withMaxDepth(int maxDepth) {
        return new ParseOptions(maxDepth, maxBlocks);
    }

    public ParseOptions withMaxBlocks(int maxBlocks) {
        return new ParseOptions(maxDepth, maxBlocks);
    }

    @Override
    public String toString() {
        return "ParseOptions[maxDepth=" + maxDepth + ", maxBlocks=" + maxBlocks + "]";
    }
}
