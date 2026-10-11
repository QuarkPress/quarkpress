package org.quarkpress.lib.phpserialize;

/** PHP boolean, serialized as {@code b:1;} or {@code b:0;}. */
public record PhpBool(boolean value) implements PhpValue {

    public static final PhpBool TRUE = new PhpBool(true);
    public static final PhpBool FALSE = new PhpBool(false);

    public static PhpBool of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
