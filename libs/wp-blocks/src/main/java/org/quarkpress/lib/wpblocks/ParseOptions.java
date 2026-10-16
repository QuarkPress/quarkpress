package org.quarkpress.lib.wpblocks;

/**
 * Parser limits. Start from {@link #DEFAULTS} and derive with the {@code with*} methods; there is
 * deliberately no public constructor so that new options can be added compatibly.
 */
public final class ParseOptions {

    /** Hard upper bound for {@link #maxDepth()}; the serializer is recursive and must stay stack-safe. */
    public static final int MAX_DEPTH_LIMIT = 1024;

    /** No WordPress limit corresponds to these; they bound memory and stack for hostile input only. */
    public static final ParseOptions DEFAULTS = new ParseOptions(MAX_DEPTH_LIMIT, 1_000_000, 8_000_000);

    private final int maxDepth;
    private final int maxBlocks;
    private final int maxRecoveredChars;

    private ParseOptions(int maxDepth, int maxBlocks, int maxRecoveredChars) {
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH_LIMIT);
        }
        if (maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be >= 1");
        }
        if (maxRecoveredChars < 0) {
            throw new IllegalArgumentException("maxRecoveredChars must be >= 0");
        }
        this.maxDepth = maxDepth;
        this.maxBlocks = maxBlocks;
        this.maxRecoveredChars = maxRecoveredChars;
    }

    /** Maximum nesting depth of blocks (1..{@link #MAX_DEPTH_LIMIT}). */
    public int maxDepth() {
        return maxDepth;
    }

    /** Maximum number of blocks, freeform blocks included, in one document. */
    public int maxBlocks() {
        return maxBlocks;
    }

    /**
     * Maximum total number of characters copied into {@linkplain Block#isRecoveredCopy() recovered copies}: the
     * last chunks and leading text of blocks that WordPress emits a second time when several blocks are left open
     * at the end of a document. Each such block repeats the rest of the document, so without a bound memory grows
     * with depth times length.
     */
    public int maxRecoveredChars() {
        return maxRecoveredChars;
    }

    public ParseOptions withMaxDepth(int maxDepth) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars);
    }

    public ParseOptions withMaxBlocks(int maxBlocks) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars);
    }

    public ParseOptions withMaxRecoveredChars(int maxRecoveredChars) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars);
    }

    @Override
    public String toString() {
        return "ParseOptions[maxDepth=" + maxDepth + ", maxBlocks=" + maxBlocks + ", maxRecoveredChars="
                + maxRecoveredChars + "]";
    }
}
