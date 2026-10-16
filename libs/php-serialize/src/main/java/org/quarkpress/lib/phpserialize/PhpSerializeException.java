package org.quarkpress.lib.phpserialize;

/** Thrown when input is not valid PHP serialized data or exceeds the configured limits. */
public class PhpSerializeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    public PhpSerializeException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** For problems not tied to an input position, such as a reference cycle found while converting a tree. */
    public PhpSerializeException(String message) {
        super(message);
        this.offset = -1;
    }

    /** Byte offset in the input at which the problem was detected, or -1 when it is not tied to the input. */
    public int offset() {
        return offset;
    }
}
