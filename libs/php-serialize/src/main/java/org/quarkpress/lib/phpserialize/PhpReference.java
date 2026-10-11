package org.quarkpress.lib.phpserialize;

/**
 * Back-reference to an earlier value, 1-based in order of appearance. {@code r:N;} copies an
 * object handle; {@code R:N;} is a PHP reference. This library keeps references symbolic and does
 * not resolve them.
 */
public record PhpReference(long index, Kind kind) implements PhpValue {

    public enum Kind {
        /** {@code r:} */
        OBJECT,
        /** {@code R:} */
        REFERENCE
    }
}
