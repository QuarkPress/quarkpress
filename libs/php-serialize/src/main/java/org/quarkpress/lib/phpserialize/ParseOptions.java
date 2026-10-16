package org.quarkpress.lib.phpserialize;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parser settings. Start from {@link #DEFAULTS} (strict) or {@link #PHP_COMPATIBLE} and derive with the {@code
 * with*} methods; there is deliberately no public constructor so that new options can be added compatibly.
 */
public final class ParseOptions {

    /**
     * Hard upper bound for {@link #maxDepth()}. The parser is recursive, so nesting is bounded by the calling
     * thread's stack; this limit keeps the default 512 KiB stacks safe with margin.
     */
    public static final int MAX_DEPTH_LIMIT = 1024;

    /** Strict grammar: exactly one value, 64-bit integers, no legacy forms. */
    public static final ParseOptions DEFAULTS =
            new ParseOptions(StandardCharsets.UTF_8, 256, 1_000_000L, 64 * 1024 * 1024, false, false, false, false);

    /**
     * Accepts what PHP 8.4's {@code unserialize()} accepts beyond the strict grammar: bytes after a complete
     * value are ignored, integers outside the 64-bit range are clamped to {@code Long.MIN_VALUE}/{@code
     * Long.MAX_VALUE}, and the legacy {@code S:} strings with {@code \xx} escapes are read. Limits are those of
     * {@link #DEFAULTS}.
     */
    public static final ParseOptions PHP_COMPATIBLE =
            DEFAULTS.withAllowTrailingData(true).withClampIntegers(true).withLegacyEscapedStrings(true);

    private final Charset charset;
    private final int maxDepth;
    private final long maxElements;
    private final int maxStringBytes;
    private final boolean repairLengths;
    private final boolean allowTrailingData;
    private final boolean clampIntegers;
    private final boolean legacyEscapedStrings;

    private ParseOptions(
            Charset charset,
            int maxDepth,
            long maxElements,
            int maxStringBytes,
            boolean repairLengths,
            boolean allowTrailingData,
            boolean clampIntegers,
            boolean legacyEscapedStrings) {
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
        this.allowTrailingData = allowTrailingData;
        this.clampIntegers = clampIntegers;
        this.legacyEscapedStrings = legacyEscapedStrings;
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
     * When true, strings whose declared byte length is wrong (common after a naive search-and-replace on
     * serialized data) are recovered instead of rejected. A QuarkPress extension; PHP has no such mode.
     */
    public boolean repairLengths() {
        return repairLengths;
    }

    /** When true, bytes after the first complete value are ignored, as PHP does (with a warning). */
    public boolean allowTrailingData() {
        return allowTrailingData;
    }

    /** When true, {@code i:} values and keys outside the 64-bit range are clamped, as PHP 8 does. */
    public boolean clampIntegers() {
        return clampIntegers;
    }

    /** When true, the legacy {@code S:len:"…";} form, where a backslash and two hex digits stand for a byte, is read. */
    public boolean legacyEscapedStrings() {
        return legacyEscapedStrings;
    }

    public ParseOptions withCharset(Charset charset) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withMaxDepth(int maxDepth) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withMaxElements(long maxElements) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withMaxStringBytes(int maxStringBytes) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withRepairLengths(boolean repairLengths) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withAllowTrailingData(boolean allowTrailingData) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withClampIntegers(boolean clampIntegers) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    public ParseOptions withLegacyEscapedStrings(boolean legacyEscapedStrings) {
        return new ParseOptions(
                charset,
                maxDepth,
                maxElements,
                maxStringBytes,
                repairLengths,
                allowTrailingData,
                clampIntegers,
                legacyEscapedStrings);
    }

    @Override
    public String toString() {
        return "ParseOptions[charset=" + charset + ", maxDepth=" + maxDepth + ", maxElements=" + maxElements
                + ", maxStringBytes=" + maxStringBytes + ", repairLengths=" + repairLengths + ", allowTrailingData="
                + allowTrailingData + ", clampIntegers=" + clampIntegers + ", legacyEscapedStrings="
                + legacyEscapedStrings + "]";
    }
}
