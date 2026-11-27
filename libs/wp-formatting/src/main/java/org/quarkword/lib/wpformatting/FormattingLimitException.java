package org.quarkword.lib.wpformatting;

/**
 * Thrown when a formatting function would build a text longer than its configured limit. WordPress has no such
 * limit: it keeps building until PHP runs out of memory.
 */
public final class FormattingLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long requiredLength;
    private final long allowedLength;

    FormattingLimitException(String function, long requiredLength, long allowedLength) {
        super(function + " would build " + requiredLength + " characters, more than the limit of " + allowedLength);
        this.requiredLength = requiredLength;
        this.allowedLength = allowedLength;
    }

    /** The length, in chars, the text would have reached ({@link Long#MAX_VALUE} if it would overflow a long). */
    public long requiredLength() {
        return requiredLength;
    }

    /** The limit, in chars, that was in force for this call. */
    public long allowedLength() {
        return allowedLength;
    }
}
