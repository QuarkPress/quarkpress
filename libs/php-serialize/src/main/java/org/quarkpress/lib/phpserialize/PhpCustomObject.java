package org.quarkpress.lib.phpserialize;

import java.util.Arrays;
import java.util.Objects;

/**
 * Object using PHP's {@code Serializable} interface, serialized as {@code C:len:"Class":plen:{payload}}.
 * The payload is opaque bytes.
 */
public record PhpCustomObject(String className, byte[] payload) implements PhpValue {

    public PhpCustomObject {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpCustomObject other
                && className.equals(other.className)
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * className.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "PhpCustomObject[" + className + ", " + payload.length + " bytes]";
    }
}
