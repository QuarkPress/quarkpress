package org.quarkpress.lib.wpblocks;

/**
 * Parser limits. Start from {@link #DEFAULTS} and derive with the {@code with*} methods; there is
 * deliberately no public constructor so that new options can be added compatibly.
 */
public final class ParseOptions {

    /** Hard upper bound for {@link #maxDepth()}; the serializer is recursive and must stay stack-safe. */
    public static final int MAX_DEPTH_LIMIT = 1024;

    /** No WordPress limit corresponds to these; they bound memory and stack for hostile input only. */
    public static final ParseOptions DEFAULTS = new ParseOptions(MAX_DEPTH_LIMIT, 1_000_000, 8_000_000, 16);

    private final int maxDepth;
    private final int maxBlocks;
    private final int maxRecoveredChars;
    private final int recoveredCharsPerInputChar;

    private ParseOptions(int maxDepth, int maxBlocks, int maxRecoveredChars, int recoveredCharsPerInputChar) {
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH_LIMIT);
        }
        if (maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be >= 1");
        }
        if (maxRecoveredChars < 0) {
            throw new IllegalArgumentException("maxRecoveredChars must be >= 0");
        }
        if (recoveredCharsPerInputChar < 0) {
            throw new IllegalArgumentException("recoveredCharsPerInputChar must be >= 0");
        }
        this.maxDepth = maxDepth;
        this.maxBlocks = maxBlocks;
        this.maxRecoveredChars = maxRecoveredChars;
        this.recoveredCharsPerInputChar = recoveredCharsPerInputChar;
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
     * The floor of the budget for {@linkplain Block#isRecoveredCopy() recovered copies}: the last chunks and
     * leading text of blocks that WordPress emits a second time when several blocks are left open at the end of a
     * document. Each such block repeats the rest of the document, so without a bound memory grows with depth times
     * length. The total number of characters copied may reach the larger of this value and {@link
     * #recoveredCharsPerInputChar()} times the input length.
     */
    public int maxRecoveredChars() {
        return maxRecoveredChars;
    }

    /**
     * How many characters the recovered copies may repeat per input character (default 16), so that the budget
     * grows with the document; see {@link #maxRecoveredChars()}.
     */
    public int recoveredCharsPerInputChar() {
        return recoveredCharsPerInputChar;
    }

    /** The number of characters the recovered copies of a document of {@code inputLength} characters may repeat. */
    long recoveredCharsBudget(int inputLength) {
        return Math.max(maxRecoveredChars, (long) recoveredCharsPerInputChar * inputLength);
    }

    public ParseOptions withMaxDepth(int maxDepth) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars, recoveredCharsPerInputChar);
    }

    public ParseOptions withMaxBlocks(int maxBlocks) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars, recoveredCharsPerInputChar);
    }

    public ParseOptions withMaxRecoveredChars(int maxRecoveredChars) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars, recoveredCharsPerInputChar);
    }

    public ParseOptions withRecoveredCharsPerInputChar(int recoveredCharsPerInputChar) {
        return new ParseOptions(maxDepth, maxBlocks, maxRecoveredChars, recoveredCharsPerInputChar);
    }

    @Override
    public String toString() {
        return "ParseOptions[maxDepth=" + maxDepth + ", maxBlocks=" + maxBlocks + ", maxRecoveredChars="
                + maxRecoveredChars + ", recoveredCharsPerInputChar=" + recoveredCharsPerInputChar + "]";
    }
}
