package org.quarkpress.lib.wpblocks;

/**
 * Thrown when a document exceeds a {@link ParseOptions} limit, and only then: malformed markup is otherwise read as
 * WordPress reads it.
 */
public final class BlockParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    BlockParseException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** Character offset in the input at which the limit was exceeded. */
    public int offset() {
        return offset;
    }
}
