package org.quarkpress.lib.phpserialize;

/** Thrown when input is not valid PHP serialized data or exceeds the configured limits. */
public class PhpSerializeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    public PhpSerializeException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** Byte offset in the input at which the problem was detected. */
    public int offset() {
        return offset;
    }
}
