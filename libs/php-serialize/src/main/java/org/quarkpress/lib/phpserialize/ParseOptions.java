package org.quarkpress.lib.phpserialize;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parser settings.
 *
 * @param charset charset used to decode string payloads (and to encode {@link String} input)
 * @param maxDepth maximum nesting depth of arrays and objects
 * @param maxElements maximum total number of array/object elements in one document
 * @param maxStringBytes maximum declared length of a single string or custom-object payload
 * @param repairLengths when true, recover strings whose declared byte length is wrong (common
 *     after a naive search-and-replace on serialized data)
 */
public record ParseOptions(Charset charset, int maxDepth, long maxElements, int maxStringBytes, boolean repairLengths) {

    public static final ParseOptions DEFAULTS =
            new ParseOptions(StandardCharsets.UTF_8, 512, 1_000_000L, 64 * 1024 * 1024, false);

    public ParseOptions {
        Objects.requireNonNull(charset, "charset");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        if (maxElements < 0) {
            throw new IllegalArgumentException("maxElements must be >= 0");
        }
        if (maxStringBytes < 0) {
            throw new IllegalArgumentException("maxStringBytes must be >= 0");
        }
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
}
