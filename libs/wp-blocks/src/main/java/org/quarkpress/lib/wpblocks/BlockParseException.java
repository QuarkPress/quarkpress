package org.quarkpress.lib.wpblocks;

/** Thrown when a document exceeds the configured limits. Malformed markup never throws. */
public class BlockParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    public BlockParseException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** Character offset in the input at which the limit was exceeded. */
    public int offset() {
        return offset;
    }
}
