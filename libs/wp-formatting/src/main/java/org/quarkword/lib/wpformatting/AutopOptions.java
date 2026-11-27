package org.quarkword.lib.wpformatting;

import java.util.OptionalLong;

/**
 * Options for {@link Formatting#autop(String, AutopOptions)}. The defaults are WordPress's, plus an output limit:
 * {@code <pre>} placeholders an author typed can make WordPress's output grow with the square of the input, so the
 * text wpautop builds is capped, by default at the larger of {@value #DEFAULT_MIN_OUTPUT_LENGTH} chars (16 MiB) and
 * {@value #DEFAULT_OUTPUT_RATIO} times the input's length. Instances are immutable and thread-safe.
 */
public final class AutopOptions {

    /** The smallest default output limit, in chars: 16 MiB. */
    public static final long DEFAULT_MIN_OUTPUT_LENGTH = 16L * 1024 * 1024;

    /** The default output limit, in chars, per char of input, when that is larger. */
    public static final int DEFAULT_OUTPUT_RATIO = 64;

    private static final AutopOptions DEFAULTS = new Builder().build();

    private final boolean lineBreaks;
    private final long maxOutputLength;

    private AutopOptions(Builder builder) {
        this.lineBreaks = builder.lineBreaks;
        this.maxOutputLength = builder.maxOutputLength;
    }

    /** WordPress's defaults: line breaks on, and the default output limit. */
    public static AutopOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether single newlines become {@code <br />} (WordPress's {@code $br}, default true). */
    public boolean lineBreaks() {
        return lineBreaks;
    }

    /** The fixed output limit in chars, or empty for the default that grows with the input. */
    public OptionalLong maxOutputLength() {
        return maxOutputLength < 0 ? OptionalLong.empty() : OptionalLong.of(maxOutputLength);
    }

    /** The limit, in chars, for an input of {@code inputLength} chars. */
    long outputLimit(int inputLength) {
        if (maxOutputLength >= 0) {
            return maxOutputLength;
        }
        return Math.max(DEFAULT_MIN_OUTPUT_LENGTH, (long) DEFAULT_OUTPUT_RATIO * inputLength);
    }

    /** Builds {@link AutopOptions}; starts from the defaults. */
    public static final class Builder {

        private boolean lineBreaks = true;
        private long maxOutputLength = -1;

        private Builder() {}

        /** Whether single newlines become {@code <br />}. */
        public Builder lineBreaks(boolean lineBreaks) {
            this.lineBreaks = lineBreaks;
            return this;
        }

        /**
         * A fixed output limit in chars, replacing the default. A call whose text would grow longer throws
         * {@link FormattingLimitException}; with {@code <pre>} blocks it does so before building the text.
         */
        public Builder maxOutputLength(long chars) {
            if (chars < 0) {
                throw new IllegalArgumentException("maxOutputLength must not be negative: " + chars);
            }
            this.maxOutputLength = chars;
            return this;
        }

        public AutopOptions build() {
            return new AutopOptions(this);
        }
    }
}
