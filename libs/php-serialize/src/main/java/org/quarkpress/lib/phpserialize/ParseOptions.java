package org.quarkpress.lib.phpserialize;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parser settings. Start from {@link #DEFAULTS} and derive with the {@code with*} methods; there
 * is deliberately no public constructor so that new options can be added compatibly.
 */
public final class ParseOptions {

    /**
     * Hard upper bound for {@link #maxDepth()}. The parser is recursive, so nesting is bounded by
     * the calling thread's stack; this limit keeps the default 512 KiB stacks safe with margin.
     */
    public static final int MAX_DEPTH_LIMIT = 1024;

    public static final ParseOptions DEFAULTS =
            new ParseOptions(StandardCharsets.UTF_8, 256, 1_000_000L, 64 * 1024 * 1024, false);

    private final Charset charset;
    private final int maxDepth;
    private final long maxElements;
    private final int maxStringBytes;
    private final boolean repairLengths;

    private ParseOptions(Charset charset, int maxDepth, long maxElements, int maxStringBytes, boolean repairLengths) {
        this.charset = Objects.requireNonNull(charset, "charset");
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH_LIMIT);
        }
        if (maxElements < 0) {
            throw new IllegalArgumentException("maxElements must be >= 0");
        }
        if (maxStringBytes < 0) {
            throw new IllegalArgumentException("maxStringBytes must be >= 0");
        }
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.maxStringBytes = maxStringBytes;
        this.repairLengths = repairLengths;
    }

    /** Charset used to decode string payloads and to encode {@link String} input. */
    public Charset charset() {
        return charset;
    }

    /** Maximum nesting depth of arrays and objects (1..{@link #MAX_DEPTH_LIMIT}). */
    public int maxDepth() {
        return maxDepth;
    }

    /** Maximum total number of array and object elements in one document. */
    public long maxElements() {
        return maxElements;
    }

    /** Maximum declared length of a single string or custom-object payload. */
    public int maxStringBytes() {
        return maxStringBytes;
    }

    /**
     * When true, strings whose declared byte length is wrong (common after a naive search-and-replace
     * on serialized data) are recovered instead of rejected.
     */
    public boolean repairLengths() {
        return repairLengths;
    }

    public ParseOptions withCharset(Charset charset) {
        return new ParseOptions(charset, maxDepth, maxElements, maxStringBytes, repairLengths);
    }

    public ParseOptions withMaxDepth(int maxDepth) {
        return new ParseOptions(charset, maxDepth, maxElements, maxStringBytes, repairLengths);
    }

    public ParseOptions withMaxElements(long maxElements) {
        return new ParseOptions(charset, maxDepth, maxElements, maxStringBytes, repairLengths);
    }

    public ParseOptions withMaxStringBytes(int maxStringBytes) {
        return new ParseOptions(charset, maxDepth, maxElements, maxStringBytes, repairLengths);
    }

    public ParseOptions withRepairLengths(boolean repairLengths) {
        return new ParseOptions(charset, maxDepth, maxElements, maxStringBytes, repairLengths);
    }

    @Override
    public String toString() {
        return "ParseOptions[charset=" + charset + ", maxDepth=" + maxDepth + ", maxElements=" + maxElements
                + ", maxStringBytes=" + maxStringBytes + ", repairLengths=" + repairLengths + "]";
    }
}
