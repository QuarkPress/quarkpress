package org.quarkword.lib.phpserialize;

/** PHP {@code null}, serialized as {@code N;}. */
public enum PhpNull implements PhpValue {
    INSTANCE;

    @Override
    public String toString() {
        return "PhpNull";
    }
}
